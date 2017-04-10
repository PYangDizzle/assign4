package edu.wisc.cs.sdn.apps.l3routing;

import edu.wisc.cs.sdn.apps.util.SwitchCommands;
import net.floodlightcontroller.packet.IPv4;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.instruction.OFInstructionApplyActions;
import org.openflow.protocol.OFMatch;

import java.util.Iterator;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.wisc.cs.sdn.apps.util.Host;

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitch.PortChangeType;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceListener;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryListener;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.routing.Link;

public class L3Routing implements IFloodlightModule, IOFSwitchListener, 
		ILinkDiscoveryListener, IDeviceListener
{
	public static final String MODULE_NAME = L3Routing.class.getSimpleName();
	
	// Interface to the logging system
    private static Logger log = LoggerFactory.getLogger(MODULE_NAME);
    
    // Interface to Floodlight core for interacting with connected switches
    private IFloodlightProviderService floodlightProv;

    // Interface to link discovery service
    private ILinkDiscoveryService linkDiscProv;

    // Interface to device manager service
    private IDeviceService deviceProv;
    
    // Switch table in which rules should be installed
    public static byte table;
    
    // Map of hosts to devices
    private Map<IDevice,Host> knownHosts;

	// YANG
	private ConcurrentHashMap<IOFSwitch, LinkedList<Route>> shortestRoutesMap; 

	private synchronized void updateMap() {
		Collection<Link> links = getLinks();	
		for( Link link : links ) {
			System.err.printf( "(%d,%d)link found\n", link.getSrc(), link.getDst() );
			// add the link as the shortest path for the 2 switches
			// add frontward and backward
			LinkedList<Route> routes = null;
			Route route = new Route();

			if( shortestRoutesMap.containsKey( floodlightProv.getSwitch( link.getSrc() ) ) ) {
				routes = shortestRoutesMap.get( floodlightProv.getSwitch( link.getSrc() ) );
			}
			else {
				routes = new LinkedList<Route>();
				shortestRoutesMap.put( floodlightProv.getSwitch( link.getSrc() ), routes ); 
			}

			boolean ignoreLink = false;

			for( Route currentRoute : routes ) {
				if( currentRoute.dest.getId() == floodlightProv.getSwitch( link.getDst() ).getId( )) {
					ignoreLink = true;	
				}
			}

			if( ignoreLink ) {
				continue;
			}

			route.dest = floodlightProv.getSwitch( link.getDst() );
			route.cost = 1;
			route.dir = route.dest;
			route.portNumber = link.getSrcPort();

			routes.add( route );
			/*
			if( shortestRoutesMap.containsKey( floodlightProv.getSwitch( link.getDst() ) ) ) {
				routes = shortestRoutesMap.get( floodlightProv.getSwitch( link.getDst() ) );
			}
			else {
				routes = new LinkedList<Route>();
				shortestRoutesMap.put( floodlightProv.getSwitch( link.getDst() ), routes ); 
			}
			
			route.dest = floodlightProv.getSwitch( link.getSrc() );
			route.cost = 1;
			route.dir = route.dest;
			route.portNumber = link.getDstPort();

			routes.add( route );
			*/
		}

		LinkedList<IOFSwitch> switches = new LinkedList<IOFSwitch>();
		LinkedList<Route> newRoutes = new LinkedList<Route>();
		Map<Long, IOFSwitch> map = getSwitches();	
		boolean repeat = false;
		for( IOFSwitch sw : map.values() ) {
			// only add frontward
			// discover routes to other swithces
			// utilizing the remembered array
			System.err.println( "working with sw" + sw.getId() );
			if( !shortestRoutesMap.containsKey( sw ) ) {
				System.err.println( sw.getId() + "switch was not added in first loop" );
				shortestRoutesMap.put( sw, new LinkedList<Route>() );
			}
			else {
				for( Route frontRoute : shortestRoutesMap.get( sw ) ) {
					for( Route endRoute : shortestRoutesMap.get( frontRoute.dest ) ) {
						// concatenate frontRoute and endRoute	
						// and update if better
						if( endRoute.dest.getId() == sw.getId() ) {
							continue;
						}
						boolean addRoute = true;
						for( Route knownRoute : shortestRoutesMap.get( sw ) ) {
							if( knownRoute.dest.getId() == endRoute.dest.getId() ) {
								// already has a route
								if( knownRoute.cost > frontRoute.cost + endRoute.cost ) {
									// should replace the knownRoute
									knownRoute.cost = frontRoute.cost + endRoute.cost;
									knownRoute.dir = frontRoute.dir;
									knownRoute.portNumber = frontRoute.portNumber;
									repeat = true;
								}
								addRoute = false;
								break;
							}
						}
						
						if( addRoute ) {
							// should add a newRoute
							Route newRoute = new Route();
							newRoute.dest = endRoute.dest;
							newRoute.cost = frontRoute.cost + endRoute.cost;
							newRoute.dir = frontRoute.dir;
							newRoute.portNumber = frontRoute.portNumber;

//							shortestRoutesMap.get( sw ).add( newRoute );
							switches.add( sw );
							newRoutes.add( newRoute );
							repeat = true;
						}
					}
				}
			}
		}

		for( int i = 0; i < newRoutes.size(); ++i ) {
			shortestRoutesMap.get( switches.get( i ) ).add( newRoutes.get( i ) );
		}

		if( repeat ) {
			updateMap();
		}
		else {
				  // install rules
			//Map<Long, IOFSwitch> map = getSwitches();	
			for( IOFSwitch sw : map.values() ) {
				sw.clearAllFlowMods();
				for( Host destHost : knownHosts.values() ) {
					if( destHost.isAttachedToSwitch() == false ) {
						// detached host
						continue;
					}
					if( destHost.getSwitch().getId() == sw.getId() ) {
						OFMatch match = new OFMatch();
						match.setDataLayerType( OFMatch.ETH_TYPE_IPV4 );
						match.setNetworkDestination( destHost.getIPv4Address() );

						OFActionOutput action = new OFActionOutput( destHost.getPort() );
						List list = new ArrayList<OFActionOutput>();
						list.add( action );
						OFInstructionApplyActions actions = new OFInstructionApplyActions( list );

						SwitchCommands.installRule( sw, table, match, actions );
					}
				}
				for( Route route : shortestRoutesMap.get( sw ) ) {
						  /* I guess Switch doesn' thave IP address
					*/
					for( Host destHost : knownHosts.values() ) {
						if( destHost.isAttachedToSwitch() == false ) {
							// detached host
							continue;
						}
						if( destHost.getSwitch().getId() == route.dest.getId() ) {
							OFMatch match = new OFMatch();
							match.setDataLayerType( OFMatch.ETH_TYPE_IPV4 );
							match.setNetworkDestination( destHost.getIPv4Address() );

							OFActionOutput action = new OFActionOutput( route.portNumber );
							List list = new ArrayList<OFActionOutput>();
							list.add( action );
							OFInstructionApplyActions actions = new OFInstructionApplyActions( list );

							SwitchCommands.installRule( sw, table, match, actions );
						}
					}
				}
			}

			for( IOFSwitch sw : shortestRoutesMap.keySet() ) {
				System.err.printf( "Switch=s%d\n", sw.getId() );
				for( Route route : shortestRoutesMap.get( sw ) ) {
					System.err.printf( "\tDest=s%d, Cost=%d, Dir=s%d, Port=%d\n", route.dest.getId(), route.cost, route.dir.getId(), route.portNumber );
				}
			}
		}
		//System.err.println( shortestRoutesMap.toString() );
	}

	/**
     * Loads dependencies and initializes data structures.
     */
	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException 
	{
		log.info(String.format("Initializing %s...", MODULE_NAME));
		Map<String,String> config = context.getConfigParams(this);
        table = Byte.parseByte(config.get("table"));
        
		this.floodlightProv = context.getServiceImpl(
				IFloodlightProviderService.class);
        this.linkDiscProv = context.getServiceImpl(ILinkDiscoveryService.class);
        this.deviceProv = context.getServiceImpl(IDeviceService.class);
        
        this.knownHosts = new ConcurrentHashMap<IDevice,Host>();
		  // YANG
		  this.shortestRoutesMap = new ConcurrentHashMap<IOFSwitch, LinkedList<Route>>();
	}

	/**
     * Subscribes to events and performs other startup tasks.
     */
	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException 
	{
		log.info(String.format("Starting %s...", MODULE_NAME));
		this.floodlightProv.addOFSwitchListener(this);
		this.linkDiscProv.addListener(this);
		this.deviceProv.addListener(this);
		
		/*********************************************************************/
		/* TODO: Initialize variables or perform startup tasks, if necessary */
		// weight for each link is 1
		//
		// N/A
		//
		//
		/*********************************************************************/
	}
	
    /**
     * Get a list of all known hosts in the network.
     */
    private Collection<Host> getHosts()
    { return this.knownHosts.values(); }
	
    /**
     * Get a map of all active switches in the network. Switch DPID is used as
     * the key.
     */
	private Map<Long, IOFSwitch> getSwitches()
    { return floodlightProv.getAllSwitchMap(); }
	
    /**
     * Get a list of all active links in the network.
     */
    private Collection<Link> getLinks()
    { return linkDiscProv.getLinks().keySet(); }

    /**
     * Event handler called when a host joins the network.
     * @param device information about the host
     */
	@Override
	public void deviceAdded(IDevice device) 
	{
		Host host = new Host(device, this.floodlightProv);
		// We only care about a new host if we know its IP
		if (host.getIPv4Address() != null)
		{
			log.info(String.format("Host %s added", host.getName()));
			this.knownHosts.put(device, host);
			
			/*****************************************************************/
			/* TODO: Update routing: add rules to route to new host          */
			updateMap();	
			/*****************************************************************/
		}
	}

	/**
     * Event handler called when a host is no longer attached to a switch.
     * @param device information about the host
     */
	@Override
	public void deviceRemoved(IDevice device) 
	{
		Host host = this.knownHosts.get(device);
		if (null == host)
		{ return; }
		this.knownHosts.remove(device);
		
		log.info(String.format("Host %s is no longer attached to a switch", 
				host.getName()));
		
		/*********************************************************************/
		/* TODO: Update routing: remove rules to route to host               */
			updateMap();	
		/*********************************************************************/
	}

	/**
     * Event handler called when a host moves within the network.
     * @param device information about the host
     */
	@Override
	public void deviceMoved(IDevice device) 
	{
		Host host = this.knownHosts.get(device);
		if (null == host)
		{
			host = new Host(device, this.floodlightProv);
			this.knownHosts.put(device, host);
		}
		
		if (!host.isAttachedToSwitch())
		{
			this.deviceRemoved(device);
			return;
		}
		log.info(String.format("Host %s moved to s%d:%d", host.getName(),
				host.getSwitch().getId(), host.getPort()));
		
		/*********************************************************************/
		/* TODO: Update routing: change rules to route to host               */
		
			updateMap();	
		/*********************************************************************/
	}
	
    /**
     * Event handler called when a switch joins the network.
     * @param DPID for the switch
     */
	@Override		
	public void switchAdded(long switchId) 
	{
		IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
		log.info(String.format("Switch s%d added", switchId));
		
		/*********************************************************************/
		/* TODO: Update routing: change routing rules for all hosts          */
		updateMap();	
		/*********************************************************************/
	}

	/**
	 * Event handler called when a switch leaves the network.
	 * @param DPID for the switch
	 */
	@Override
	public void switchRemoved(long switchId) 
	{
		IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
		log.info(String.format("Switch s%d removed", switchId));
		
		/*********************************************************************/
		/* TODO: Update routing: change routing rules for all hosts          */
			shortestRoutesMap.remove( floodlightProv.getSwitch( switchId ) );
			for( Map.Entry<IOFSwitch, LinkedList<Route>> entry : shortestRoutesMap.entrySet() ) {
				Iterator<Route> itr = entry.getValue().iterator();
				while( itr.hasNext() ) {
					if( itr.next().dest.getId() == switchId ) {
						itr.remove();
					}
				}
			}
			updateMap();	
		/*********************************************************************/
	}

	/**
	 * Event handler called when multiple links go up or down.
	 * @param updateList information about the change in each link's state
	 */
	@Override
	public void linkDiscoveryUpdate(List<LDUpdate> updateList) 
	{
		for (LDUpdate update : updateList)
		{
			// If we only know the switch & port for one end of the link, then
			// the link must be from a switch to a host
			if (0 == update.getDst())
			{
				log.info(String.format("Link s%s:%d -> host updated", 
					update.getSrc(), update.getSrcPort()));
			}
			// Otherwise, the link is between two switches
			else
			{
				log.info(String.format("Link s%s:%d -> s%s:%d updated", 
					update.getSrc(), update.getSrcPort(),
					update.getDst(), update.getDstPort()));
			}
		}
		
		/*********************************************************************/
		/* TODO: Update routing: change routing rules for all hosts          */
		
		updateMap();
		/*********************************************************************/
	}

	/**
	 * Event handler called when link goes up or down.
	 * @param update information about the change in link state
	 */
	@Override
	public void linkDiscoveryUpdate(LDUpdate update) 
	{ this.linkDiscoveryUpdate(Arrays.asList(update)); }
	
	/**
     * Event handler called when the IP address of a host changes.
     * @param device information about the host
     */
	@Override
	public void deviceIPV4AddrChanged(IDevice device) 
	{ this.deviceAdded(device); }

	/**
     * Event handler called when the VLAN of a host changes.
     * @param device information about the host
     */
	@Override
	public void deviceVlanChanged(IDevice device) 
	{ /* Nothing we need to do, since we're not using VLANs */ }
	
	/**
	 * Event handler called when the controller becomes the master for a switch.
	 * @param DPID for the switch
	 */
	@Override
	public void switchActivated(long switchId) 
	{ /* Nothing we need to do, since we're not switching controller roles */ }

	/**
	 * Event handler called when some attribute of a switch changes.
	 * @param DPID for the switch
	 */
	@Override
	public void switchChanged(long switchId) 
	{ /* Nothing we need to do */ }
	
	/**
	 * Event handler called when a port on a switch goes up or down, or is
	 * added or removed.
	 * @param DPID for the switch
	 * @param port the port on the switch whose status changed
	 * @param type the type of status change (up, down, add, remove)
	 */
	@Override
	public void switchPortChanged(long switchId, ImmutablePort port,
			PortChangeType type) 
	{ /* Nothing we need to do, since we'll get a linkDiscoveryUpdate event */ }

	/**
	 * Gets a name for this module.
	 * @return name for this module
	 */
	@Override
	public String getName() 
	{ return this.MODULE_NAME; }

	/**
	 * Check if events must be passed to another module before this module is
	 * notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPrereq(String type, String name) 
	{ return false; }

	/**
	 * Check if events must be passed to another module after this module has
	 * been notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPostreq(String type, String name) 
	{ return false; }
	
    /**
     * Tell the module system which services we provide.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() 
	{ return null; }

	/**
     * Tell the module system which services we implement.
     */
	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> 
			getServiceImpls() 
	{ return null; }

	/**
     * Tell the module system which modules we depend on.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> 
			getModuleDependencies() 
	{
		Collection<Class<? extends IFloodlightService >> floodlightService =
	            new ArrayList<Class<? extends IFloodlightService>>();
        floodlightService.add(IFloodlightProviderService.class);
        floodlightService.add(ILinkDiscoveryService.class);
        floodlightService.add(IDeviceService.class);
        return floodlightService;
	}
}
