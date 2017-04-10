package edu.wisc.cs.sdn.apps.l3routing;

import net.floodlightcontroller.core.IOFSwitch;

public class Route {
	public IOFSwitch dest = null;
	public int cost = -1;
	public IOFSwitch dir = null;
	public int portNumber = -1;
}
