package edu.wisc.cs.sdn.apps.loadbalancer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Arrays;
import java.util.List;

import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFType;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFOXMFieldType;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.action.OFActionSetField;
import org.openflow.protocol.instruction.OFInstruction;
import org.openflow.protocol.instruction.OFInstructionGotoTable;
import org.openflow.protocol.instruction.OFInstructionApplyActions;
import org.openflow.protocol.OFMatch;
import edu.wisc.cs.sdn.apps.l3routing.L3Routing;
import edu.wisc.cs.sdn.apps.util.SwitchCommands;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.wisc.cs.sdn.apps.util.ArpServer;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch.PortChangeType;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.internal.DeviceManagerImpl;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.util.MACAddress;
public class LoadBalancer implements IFloodlightModule, IOFSwitchListener,
		IOFMessageListener
{
	public static final String MODULE_NAME = LoadBalancer.class.getSimpleName();
	
	private static final byte TCP_FLAG_SYN = 0x02;
	
	private static final short IDLE_TIMEOUT = 20;
	
	// Interface to the logging system
    private static Logger log = LoggerFactory.getLogger(MODULE_NAME);
    
    // Interface to Floodlight core for interacting with connected switches
    private IFloodlightProviderService floodlightProv;
    
    // Interface to device manager service
    private IDeviceService deviceProv;
    
    // Switch table in which rules should be installed
    private byte table;
    
    // Set of virtual IPs and the load balancer instances they correspond with
    private Map<Integer,LoadBalancerInstance> instances;

    /**
     * Loads dependencies and initializes data structures.
     */
	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException 
	{
		log.info(String.format("Initializing %s...", MODULE_NAME));
		
		// Obtain table number from config
		Map<String,String> config = context.getConfigParams(this);
        this.table = Byte.parseByte(config.get("table"));
        
        // Create instances from config
        this.instances = new HashMap<Integer,LoadBalancerInstance>();
        String[] instanceConfigs = config.get("instances").split(";");
        for (String instanceConfig : instanceConfigs)
        {
        	String[] configItems = instanceConfig.split(" ");
        	if (configItems.length != 3)
        	{ 
        		log.error("Ignoring bad instance config: " + instanceConfig);
        		continue;
        	}
        	LoadBalancerInstance instance = new LoadBalancerInstance(
        			configItems[0], configItems[1], configItems[2].split(","));
            this.instances.put(instance.getVirtualIP(), instance);
            log.info("Added load balancer instance: " + instance);
        }
        
		this.floodlightProv = context.getServiceImpl(
				IFloodlightProviderService.class);
        this.deviceProv = context.getServiceImpl(IDeviceService.class);
        
        /*********************************************************************/
        /* TODO: Initialize other class variables, if necessary              */
        
        /*********************************************************************/
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
		this.floodlightProv.addOFMessageListener(OFType.PACKET_IN, this);
		
		/*********************************************************************/
		/* TODO: Perform other tasks, if necessary                           */
		
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
		/* TODO: Install rules to send:                                      */
		/*       (1) packets from new connections to each virtual load       */
		/*       balancer IP to the controller                               */
		/*       (2) ARP packets to the controller, and                      */
		/*       (3) all other packets to the next rule table in the switch  */
	   /*********************************************************************/
	   for(Integer virtural : instances.keySet()){
			//(1)
			OFMatch newMatch = new OFMatch();
			newMatch.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
			newMatch.setNetworkProtocol(OFMatch.IP_PROTO_TCP);
			OFAction action = new OFActionOutput(OFPort.OFPP_CONTROLLER);
			OFInstruction instr = new OFInstructionApplyActions(Arrays.asList(action));
			List instrList = Arrays.asList(instr);
			SwitchCommands.installRule(sw, this.table, (short)(SwitchCommands.DEFAULT_PRIORITY+1), newMatch, instrList);

			//(2)
			OFMatch newMatchArp = new OFMatch();
			newMatchArp.setDataLayerType(OFMatch.ETH_TYPE_ARP);
			newMatchArp.setNetworkDestination(virtural);
			OFAction actionArp = new OFActionOutput(OFPort.OFPP_CONTROLLER);
			OFInstruction instrArp = new OFInstructionApplyActions(Arrays.asList(actionArp));
		   List instrArpList = Arrays.asList(instrArp);	
			SwitchCommands.installRule(sw, this.table, (short)(SwitchCommands.DEFAULT_PRIORITY+1), newMatch, instrArpList);
		} 	
		//(3)
		OFMatch newMatch = new OFMatch();
		OFInstruction instr = new OFInstructionGotoTable(L3Routing.table);
		List instrList = Arrays.asList(instr);
      SwitchCommands.installRule(sw, this.table, (short)SwitchCommands.DEFAULT_PRIORITY, newMatch, instrList);
	}
	
	/**
	 * Handle incoming packets sent from switches.
	 * @param sw switch on which the packet was received
	 * @param msg message from the switch
	 * @param cntx the Floodlight context in which the message should be handled
	 * @return indication whether another module should also process the packet
	 */
	@Override
	public net.floodlightcontroller.core.IListener.Command receive(
			IOFSwitch sw, OFMessage msg, FloodlightContext cntx) 
	{
		// We're only interested in packet-in messages
		if (msg.getType() != OFType.PACKET_IN)
		{ return Command.CONTINUE; }
		OFPacketIn pktIn = (OFPacketIn)msg;
		
		// Handle the packet
		Ethernet ethPkt = new Ethernet();
		ethPkt.deserialize(pktIn.getPacketData(), 0,
				pktIn.getPacketData().length);
		
		/*********************************************************************/
		/* TODO: Send an ARP reply for ARP requests for virtual IPs; for TCP */
		/*       SYNs sent to a virtual IP, select a host and install        */
		/*       connection-specific rules to rewrite IP and MAC addresses;  */
		/*       ignore all other packets                                    */
		
		/*********************************************************************/
		if(ethPkt.getEtherType() == Ethernet.TYPE_ARP){
			ARP packetArp = (ARP)ethPkt.getPayload();		  
			int virtural = IPv4.toIPv4Address(packetArp.getTargetProtocolAddress());
	
			if(this.instances.containsKey(virtural)) {
				ARP arp = new ARP();				  
		  		Ethernet eth = new Ethernet();
				byte[] macAddr = this.instances.get(virtural).getVirtualMAC();
				arp.setOpCode(ARP.OP_REPLY);
				arp.setSenderProtocolAddress(virtural);
				arp.setSenderHardwareAddress(macAddr);
				arp.setProtocolType(packetArp.getProtocolType());
				arp.setProtocolAddressLength(packetArp.getProtocolAddressLength());
				arp.setHardwareType(packetArp.getHardwareType());
				arp.setHardwareAddressLength(packetArp.getHardwareAddressLength());
				arp.setTargetProtocolAddress(packetArp.getSenderProtocolAddress());
				arp.setTargetHardwareAddress(packetArp.getSenderHardwareAddress());
				eth.setEtherType(Ethernet.TYPE_ARP);
				eth.setSourceMACAddress(macAddr);
				eth.setDestinationMACAddress(ethPkt.getSourceMACAddress());
				eth.setPayload(arp);
				SwitchCommands.sendPacket(sw, (short)pktIn.getInPort(), eth);
		   }
		} else if(ethPkt.getEtherType() == Ethernet.TYPE_IPv4){
					IPv4 packetIp = (IPv4) ethPkt.getPayload();
					if(packetIp.getProtocol() == IPv4.PROTOCOL_TCP){
						TCP packetTcp = (TCP) packetIp.getPayload();
				  	   if(packetTcp.getFlags() == TCP_FLAG_SYN){
										int destIP = packetIp.getDestinationAddress();
										int nextIP = this.instances.get(destIP).getNextHostIP();
										OFMatch newMatch = new OFMatch();
										newMatch.setNetworkSource(packetIp.getSourceAddress());
										newMatch.setNetworkDestination(destIP);
										newMatch.setNetworkProtocol(OFMatch.IP_PROTO_TCP);
										newMatch.setDataLayerType(Ethernet.TYPE_IPv4);
										newMatch.setTransportSource(OFMatch.IP_PROTO_TCP, packetTcp.getSourcePort());
										newMatch.setTransportDestination(OFMatch.IP_PROTO_TCP, packetTcp.getDestinationPort());
										OFAction ip = new OFActionSetField(OFOXMFieldType.IPV4_DST, nextIP);
										OFAction mac = new OFActionSetField(OFOXMFieldType.ETH_DST, this.getHostMACAddress(nextIP));
										OFInstruction instruction =  new OFInstructionApplyActions(Arrays.asList(ip, mac));
							     		OFInstruction defaultInst = new OFInstructionGotoTable(L3Routing.table);
				 						SwitchCommands.installRule(sw, table, (short)(SwitchCommands.DEFAULT_PRIORITY + 2), newMatch,Arrays.asList(instruction, defaultInst), SwitchCommands.NO_TIMEOUT, IDLE_TIMEOUT);
										newMatch = new OFMatch();
										newMatch.setNetworkSource(nextIP);
										newMatch.setNetworkDestination(packetIp.getSourceAddress());
										newMatch.setNetworkProtocol(OFMatch.IP_PROTO_TCP);
										newMatch.setDataLayerType(Ethernet.TYPE_IPv4);
										newMatch.setTransportSource(OFMatch.IP_PROTO_TCP, packetTcp.getDestinationPort());
										newMatch.setTransportDestination(OFMatch.IP_PROTO_TCP, packetTcp.getSourcePort());
										ip = new OFActionSetField(OFOXMFieldType.IPV4_SRC, destIP);
										mac = new OFActionSetField(OFOXMFieldType.ETH_SRC, this.instances.get(destIP).getVirtualMAC());
										instruction =  new OFInstructionApplyActions(Arrays.asList(ip, mac));
										SwitchCommands.installRule(sw, table, (short)(SwitchCommands.DEFAULT_PRIORITY + 2),newMatch,
										Arrays.asList(instruction, defaultInst), SwitchCommands.NO_TIMEOUT, IDLE_TIMEOUT);
								}
								}
		  }
		
		//We don't care about other packets
		return Command.CONTINUE;
	}
	
	/**
	 * Returns the MAC address for a host, given the host's IP address.
	 * @param hostIPAddress the host's IP address
	 * @return the hosts's MAC address, null if unknown
	 */
	private byte[] getHostMACAddress(int hostIPAddress)
	{
		Iterator<? extends IDevice> iterator = this.deviceProv.queryDevices(
				null, null, hostIPAddress, null, null);
		if (!iterator.hasNext())
		{ return null; }
		IDevice device = iterator.next();
		return MACAddress.valueOf(device.getMACAddress()).toBytes();
	}

	/**
	 * Event handler called when a switch leaves the network.
	 * @param DPID for the switch
	 */
	@Override
	public void switchRemoved(long switchId) 
	{ /* Nothing we need to do, since the switch is no longer active */ }

	/**
	 * Event handler called when the controller becomes the master for a switch.
	 * @param DPID for the switch
	 */
	@Override
	public void switchActivated(long switchId)
	{ /* Nothing we need to do, since we're not switching controller roles */ }

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
	{ /* Nothing we need to do, since load balancer rules are port-agnostic */}

	/**
	 * Event handler called when some attribute of a switch changes.
	 * @param DPID for the switch
	 */
	@Override
	public void switchChanged(long switchId) 
	{ /* Nothing we need to do */ }
	
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
        floodlightService.add(IDeviceService.class);
        return floodlightService;
	}

	/**
	 * Gets a name for this module.
	 * @return name for this module
	 */
	@Override
	public String getName() 
	{ return MODULE_NAME; }

	/**
	 * Check if events must be passed to another module before this module is
	 * notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) 
	{
		return (OFType.PACKET_IN == type 
				&& (name.equals(ArpServer.MODULE_NAME) 
					|| name.equals(DeviceManagerImpl.MODULE_NAME))); 
	}

	/**
	 * Check if events must be passed to another module after this module has
	 * been notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) 
	{ return false; }
}
