package edu.wisc.cs.sdn.apps.l3routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openflow.protocol.*;
import org.openflow.protocol.action.*;
import org.openflow.protocol.instruction.*;

import edu.wisc.cs.sdn.apps.util.Host;
import edu.wisc.cs.sdn.apps.util.SwitchCommands;

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

	// Network Topology graph
	private NetworkGraph graph;

	// Bellman Ford Table : Mapping between switches with respect to shortest path
	private HashMap<String, HashMap<String, String>> bellFordTable;

	// Rule Table : IP <-> Output port mapping - Local Datastructure
	private HashMap<String, HashMap<Integer, Integer>> ruleTable;

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
		/* TODO[DONE]: Initialize variables or perform startup tasks, if necessary */
		// Initializing Graph which tracks the network topology
		graph = new NetworkGraph(0, 0);

		// Initializing ruleTable which tracks IP - Out port for every switch
		ruleTable = new HashMap<String, HashMap<Integer, Integer>>();

		// Updates the network topology
		// Has no-effect since the network toplogy is empty
		// Added to check if there was a instance running before starting floodlight
		updateNetworkTopology();

		// Runs BellmanFord algorithm and return shortest path table
		bellFordTable = graph.runBellmanFord();
		// Converts Shortest Path to IP <-> out port mapping if there are hosts in the network
		updateRuleTable();

		// Installs all the computed rules into the SDN switches as OpenFlow rules
		installRulesInSwitches();
		System.out.println("Completed Initializing task");
		/*********************************************************************/
		
	}

	public void printRuleTable() {
		// Generated rule shortestPathTablele for each switch
		for(String s : ruleTable.keySet()) {
			System.out.println("--------------------------");
			System.out.println("Table for " + s);
			System.out.println("--------------------------");
			HashMap<Integer, Integer> rules = ruleTable.get(s);
			for(Integer ip : rules.keySet()) {
				System.out.println(fromIPv4Address(ip) + ": eth" + rules.get(ip));
			}
			System.out.println("--------------------------");
		}
		//System.out.println(ruleTable);
	}

	public void updateRuleTableForHost(Host h) {
			// If a host is not attached to a switch, ignore the switch
			if(!h.isAttachedToSwitch()) {
				//System.out.println("Not attached to sw");
				return;
			}

			String switchName = "s" + String.valueOf(h.getSwitch().getId());
			for(String sw : bellFordTable.keySet()) {
				HashMap<String, String> e = bellFordTable.get(sw);
				if(e.containsKey(switchName)) {
					if(ruleTable.containsKey(sw)) {
						HashMap<Integer, Integer> currMap = ruleTable.get(sw);
						currMap.put(h.getIPv4Address(), Integer.valueOf(e.get(switchName)));
						ruleTable.put(sw, currMap);
					}
					else {
						HashMap<Integer, Integer> newMap = new HashMap<Integer, Integer>();
						newMap.put(h.getIPv4Address(), Integer.valueOf(e.get(switchName)));
						ruleTable.put(sw, newMap);
					}
				}
				else {
					//System.out.println(sw + " doesnt contain " + switchName);
				}
			}
			if(ruleTable.containsKey(switchName)) {
				HashMap<Integer, Integer> currMap = ruleTable.get(switchName);
				currMap.put(h.getIPv4Address(), h.getPort());
				ruleTable.put(switchName, currMap);
			} else {
				HashMap<Integer, Integer> newMap = new HashMap<Integer, Integer>();
				newMap.put(h.getIPv4Address(), h.getPort());
				ruleTable.put(switchName, newMap);
			}
	}

	// Function which converts Shortest Path to IP <-> out port mapping if there are hosts in the network
	// ruleTable is updated at the end of this function
	public void updateRuleTable() {
		ruleTable = new HashMap<String, HashMap<Integer, Integer>>();
		Collection<Host> hosts = getHosts();
		if(hosts.size() == 0) {
			//System.out.println("No hosts in network");
			return;
		}

		for(Host h : hosts) {
			updateRuleTableForHost(h);
		}
	}

	public void getCurrentHosts() {
		System.out.println("------------------------------");
		System.out.println("Host Info");
		System.out.println("------------------------------");
		Collection<Host> hosts = getHosts();
		for(Host h : hosts) {
			System.out.println(fromIPv4Address(h.getIPv4Address()) + " : " + h.getIPv4Address() + " : " + h.getMACAddress());
		}
		System.out.println("------------------------------");
	}

	// Function updates ruleTable by removing entries for a particular IP
	public void removeRuleFromRuleTable(int IPAddress) {
		for(String switchName : ruleTable.keySet()) {
			HashMap<Integer, Integer> rules = ruleTable.get(switchName);
			if(rules.containsKey(IPAddress))
				rules.remove(IPAddress);
		}
	}

	// Function removes Open Flow rules from every switch for an IP Address
	public void removeRuleForHost(int IPAddress) {
		Map<Long, IOFSwitch> switches = getSwitches();

		for(Long i : switches.keySet()) {
			IOFSwitch currSwitch = switches.get(i);
			
			OFMatch match = new OFMatch();
			match.setDataLayerType((short)0x800);
			match.setNetworkDestination(IPAddress);

			SwitchCommands.removeRules(currSwitch, table, match);
		}
	}

	// Function which install rules in all SDN Switches with respect to the computed
	// ruletable using Bellman ford algorithm
	public void installRulesInSwitches() {

		if(bellFordTable.isEmpty()) {
			//System.out.println("No rules to apply");
			return;
		}

		for(String s : ruleTable.keySet()) {
			Map<Long, IOFSwitch> switches = getSwitches();
			IOFSwitch currSwitch = null;
			boolean foundSwitch = false;
			for(Long i : switches.keySet()) {
				String currSwitchName = "s" + switches.get(i).getId();
				if(currSwitchName.equals(s)) {
					currSwitch = switches.get(i);
					foundSwitch = true;
					break;
				}
			}

			if(foundSwitch == false)
				continue;

			HashMap<Integer, Integer> entries = ruleTable.get(s);
			if(entries == null || entries.size() == 0)
				continue;

			//System.out.println("Installing rules for s" + currSwitch.getId());
			for(Integer ip : entries.keySet()) {
				int port = entries.get(ip);
				//System.out.println(fromIPv4Address(ip) + ":" + port);

				OFMatch match = new OFMatch();
				match.setDataLayerType((short)0x800);
				match.setField(OFOXMFieldType.IPV4_DST, ip);
				//match.setNetworkDestination(ip);

				OFActionOutput OFOut = new OFActionOutput();
				OFOut.setPort(port);
				List<OFAction> actions = new ArrayList<OFAction>();
				actions.add(OFOut);
				OFInstructionApplyActions action = new OFInstructionApplyActions(actions);
				List<OFInstruction> instr = new ArrayList<OFInstruction>();
				instr.add(action);

				// Install Open Flow rules to foward packets
				boolean ret = SwitchCommands.installRule(currSwitch, table, SwitchCommands.DEFAULT_PRIORITY,
									match, instr, SwitchCommands.NO_TIMEOUT, SwitchCommands.NO_TIMEOUT, OFPacketOut.BUFFER_ID_NONE);
				//System.out.println("Return Value : " + ret);
				//System.out.println(fromIPv4Address(ip) + " : eth" + port);
				//System.out.println(currSwitch.getId() + " : " + ip + " : " + port);
			}
		}
	}

	/* Updates the network graph */
	public void updateNetworkTopology() {
		Map<Long, IOFSwitch> currentSwitches = getSwitches();
		Collection<Link> currentLinks = getLinks();

		if(!currentSwitches.isEmpty()) {
			for(Long i : currentSwitches.keySet()) {
				IOFSwitch sw = currentSwitches.get(i);
				String switchName = "s" + sw.getId();
				this.graph.addVertex(switchName);
			}
		} else {
//			System.out.println("No switches in the network");
		}

		if(!currentLinks.isEmpty()) {
			for(Link l : currentLinks) {
				// Should add links only between switches
				// Link between switches & host will have dst == 0 : see linkDiscoveryUpdate()
				if(l.getDst() != 0) {
					this.graph.addEdge("s" + l.getSrc(), "s" + l.getDst(), l.getSrcPort(), l.getDstPort());
				}
			}
		} else {
//			System.out.println("No links in the network");
		}
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
		System.out.println("Device added");
		Host host = new Host(device, this.floodlightProv);
		// We only care about a new host if we know its IP
		if (host.getIPv4Address() != null)
		{
			log.info(String.format("Host %s added", host.getName()));
			this.knownHosts.put(device, host);
			
			/*****************************************************************/
			/* TODO[DONE]: Update routing: add rules to route to new host          */
			/* For every host addition, the local ruleTable is updated using the 
			 * bellmanford table for that host*/
			if(bellFordTable.isEmpty())
				bellFordTable = graph.runBellmanFord();

			updateRuleTableForHost(host);

			/* Updated rules are installed in the switches */
			installRulesInSwitches();
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
		/* TODO[DONE]: Update routing: remove rules to route to host               */
		System.out.println("Removing rules & route talbe intries : " + fromIPv4Address(host.getIPv4Address()));

		/* Update the ruleTable for that particular host */
		removeRuleForHost(host.getIPv4Address());

		/* Remove flow table rules from every switch */
		removeRuleFromRuleTable(host.getIPv4Address()); 
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
		/* TODO[DONE]: Update routing: change rules to route to host               */
		/* Similar to host addition but triggered when a host goes down and comes up */
		System.out.println("Updating rules " + host.getName());

		updateRuleTableForHost(host);
		installRulesInSwitches();
		/*********************************************************************/
	}
	
    /**
     * Event handler called when a switch joins the network.
     * @param DPID for the switch
     */
	@Override		
	public void switchAdded(long switchId) 
	{
		System.out.println("Switch added");
		IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
		log.info(String.format("Switch s%d added", switchId));
		
		/*********************************************************************/
		/* TODO[DONE]: Update routing: change routing rules for all hosts          */

		// Add vertex as s<Switch ID> in the network graph
		// Since a link may not be created when a switch is added, no further 
		// computation is required at this stage
		String vertexName = "s" + sw.getId();
		graph.addVertex(vertexName);
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
		/* TODO[DONE]: Update routing: change routing rules for all hosts          */
		/* Update the network topology */
		String vertexName = "s" + switchId;
		graph.removeVertex(vertexName);
		graph.clearEdgesOfVertex(vertexName);

		/* Remove rules corresponding to all hosts attached to remoevd Switch */ 
		/* Get list of Hosts connected to switch */
		Collection<Host> hosts = getHosts();
		List<Host> listOfAttachedHosts = new LinkedList<Host>();
		for(Host h: hosts) {
			IOFSwitch currSwitch = h.getSwitch();
			if(currSwitch != null && currSwitch.getId() == switchId) {
				listOfAttachedHosts.add(h);
			}
		}

		/* Update Rule Table Data Structure */
		for(Host h: listOfAttachedHosts) {
			removeRuleFromRuleTable(h.getIPv4Address());
		}

		/* Remove Open Flow rules in every swtich for all the removed hosts */
		for(Host h: listOfAttachedHosts) {
			removeRuleForHost(h.getIPv4Address());
		}

		/* Now that a switch is remoed, there can be other paths associated for
		   the hosts attached to the switches */
		/* Compute Bellman Ford again since the topology has changed */
		bellFordTable = graph.runBellmanFord();

		// Update rule table with respect to new topology
		updateRuleTable();

		// Install Rules for all the hosts in the networks in each switch
		installRulesInSwitches();
		/*********************************************************************/
	}

	/**
	 * Event handler called when multiple links go up or down.
	 * @param updateList information about the change in each link's state
	 */
	@Override
	public void linkDiscoveryUpdate(List<LDUpdate> updateList) 
	{
		boolean isSwitchLinkUpdate = false;
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

				String u = "s" + update.getSrc();
				String v = "s" + update.getDst();
				if(update.getOperation().toString().equals("Link Updated")) {
					/* Case 1: Switch link added after a switch is added */
					/* So, change in topology and hence recompute bellman ford for and update rules */
					graph.addEdge(u, v, update.getSrcPort(), update.getDstPort());
					isSwitchLinkUpdate = true;
				} else if(update.getOperation().toString().equals("Link Removed")) {
					/* Case 2: Link is removed before a switch is removed */
					graph.removeEdge(u, v);
					isSwitchLinkUpdate = true;
				}
			}
		}


		/*********************************************************************/
		/* TODO[DONE]: Update routing: change routing rules for all hosts          */
		if(isSwitchLinkUpdate) {
			bellFordTable = graph.runBellmanFord();
			updateRuleTable();
			installRulesInSwitches();
		}
		/*********************************************************************/
	}

	// Function which converts int ip address to IP Address format */
	public static String fromIPv4Address(int ipAddress) {
        StringBuffer sb = new StringBuffer();
        int result = 0;
        for (int i = 0; i < 4; ++i) {
            result = (ipAddress >> ((3-i)*8)) & 0xff;
            sb.append(Integer.valueOf(result).toString());
            if (i != 3)
                sb.append(".");
        }
        return sb.toString();
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
