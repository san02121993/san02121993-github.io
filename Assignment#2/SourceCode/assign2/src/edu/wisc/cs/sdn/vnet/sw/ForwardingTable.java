package edu.wisc.cs.sdn.vnet.sw;

import net.floodlightcontroller.packet.MACAddress;
import edu.wisc.cs.sdn.vnet.Iface;
import java.lang.Thread;
import java.util.*;

public class ForwardingTable extends Thread {
	List<ForwardingTableRecord> fTable;

	ForwardingTable(){
		fTable  = new ArrayList<ForwardingTableRecord>();
		this.start();
	}

	public void learnForwarding(MACAddress input, Iface intf){
		System.out.println("*** MAC table Learning ***");

		synchronized(this.fTable) {
		if(fTable.size() == 0) {
			ForwardingTableRecord r = new ForwardingTableRecord(input, intf);
			fTable.add(r);
		} else {
			for(ForwardingTableRecord record: this.fTable){
				if(record.inputMAC.equals(input)){
					record.startTime = System.currentTimeMillis();
					System.out.println("Found entry updating time : " + record.inputMAC);
					return;
				}
			}
			ForwardingTableRecord r = new ForwardingTableRecord(input, intf);
			fTable.add(r);
		}
		}
	}

	public Iface getIFaceForMAC(MACAddress inputMAC) {
		synchronized(this.fTable) {
		for(ForwardingTableRecord r:fTable) {
			if(r.inputMAC.equals(inputMAC)) {
				return r.inIface;
			}
		}
		}
		return null;
	}

	public void run() {
		try {
			while(true) {
				Thread.sleep(1000);
				if(this.fTable.size() == 0 || this.fTable == null)
					continue;

				long now = System.currentTimeMillis();
				synchronized(this.fTable) {
				Iterator itr = fTable.iterator();
				while(itr.hasNext()) {
					ForwardingTableRecord r = (ForwardingTableRecord)itr.next();
					int diffTime = (int)((now - r.startTime) / 1000);
					if(diffTime > r.timeOut) {
						System.out.println("Removing " + r.inputMAC);
						itr.remove();
					}
				}
				}
			}
		} catch (Exception e) {
			System.out.println(e);
		}
	}

	public String toString() {
		synchronized(this.fTable) {
			if(this.fTable.size() == 0 || this.fTable == null)
				return "Empty";

			String result = "MAC Address\t\tIFace\tTimeout\tStartTime\n";
			for(ForwardingTableRecord r: this.fTable) {
				result += r.toString() + "\n";
			}
			return result;
		}
	}
}
