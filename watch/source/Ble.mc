import Toybox.Application;
import Toybox.BluetoothLowEnergy;
import Toybox.Lang;
import Toybox.System;
import Toybox.Timer;
import Toybox.WatchUi;

// Direct Bluetooth link to TCS on the phone (PROTOCOL.md section 6). The phone advertises a TCS beacon and runs a
// GATT server; the watch scans, connects, writes messages to RX and gets the phone's messages as TX notifications.
// Garmin Connect / Gadgetbridge are not involved. Only while the app is open.
(:ble)
module Ble {
    const SVC = "7c3e0001-5b1a-4c8e-9f2d-3a6b8e1c4d70";
    const RX  = "7c3e0002-5b1a-4c8e-9f2d-3a6b8e1c4d70";     // watch -> phone (write with response)
    const TX  = "7c3e0003-5b1a-4c8e-9f2d-3a6b8e1c4d70";     // phone -> watch (notify)
    const SCAN_ON_MS = 30000;                                // scan 30 s, rest 30 s, while not connected
    const SCAN_OFF_MS = 30000;
    const MAX_BOATS = 4;

    // state
    const OFF = 0; const SCANNING = 1; const RESTING = 2; const CONNECTING = 3; const READY = 4;
    var state as Number = OFF;
    var supported as Boolean = true;
    var since as Number = 0;                         // ms, when the current state began
    var boats as Array<Array> = [];                  // [boatId, label, rssi, scanResult, seenMs]
    var device as BluetoothLowEnergy.Device? = null;
    var rx as BluetoothLowEnergy.Characteristic? = null;
    var queue as Array<ByteArray> = [];
    var busy as Boolean = false;
    var counter as Number = 0;
    var reasm as Wire.Reassembler = new Wire.Reassembler();
    var target as Array? = null;                     // the boat being connected to
    var registered as Boolean = false;

    function start() as Void {
        if (!(Toybox has :BluetoothLowEnergy)) { supported = false; Log.p("ble unsupported"); return; }
        try {
            BluetoothLowEnergy.setDelegate(new TcsBleDelegate());
            BluetoothLowEnergy.registerProfile({
                :uuid => BluetoothLowEnergy.stringToUuid(SVC),
                :characteristics => [
                    { :uuid => BluetoothLowEnergy.stringToUuid(RX) },
                    { :uuid => BluetoothLowEnergy.stringToUuid(TX), :descriptors => [BluetoothLowEnergy.cccdUuid()] }
                ]
            });
            Log.p("profile requested");
            // Some watches don't call onProfileRegister again on a later launch (the profile is still registered
            // from before). If no answer comes in 3 s, carry on and scan anyway.
            regTimer = new Timer.Timer();
            (regTimer as Timer.Timer).start(new Lang.Method(Ble, :onRegTimeout), 3000, false);
        } catch (e) {
            supported = false;
            Log.p("ble register threw");
        }
    }

    function stop() as Void {
        if (!supported) { return; }
        try {
            BluetoothLowEnergy.setScanState(BluetoothLowEnergy.SCAN_STATE_OFF);
            if (device != null) { BluetoothLowEnergy.unpairDevice(device as BluetoothLowEnergy.Device); }
        } catch (e) {}
        device = null; rx = null; state = OFF;
    }

    function setState(s as Number) as Void { state = s; since = System.getTimer(); WatchUi.requestUpdate(); }

    function scan() as Void {
        if (!supported || !registered) { return; }
        BluetoothLowEnergy.setScanState(BluetoothLowEnergy.SCAN_STATE_SCANNING);
        setState(SCANNING);
    }

    // Every 10 s from the app timer: scan duty cycle, drop boats not seen for a minute, give up stuck connects
    function tick() as Void {
        if (!supported || !registered) { return; }
        var t = System.getTimer();
        for (var i = boats.size() - 1; i >= 0; i--) {
            if (t - ((boats[i] as Array)[4] as Number) > 60000) { boats.remove(boats[i]); }
        }
        if (state == SCANNING && t - since > SCAN_ON_MS && !picking) {
            BluetoothLowEnergy.setScanState(BluetoothLowEnergy.SCAN_STATE_OFF);
            setState(RESTING);
        } else if (state == RESTING && t - since > SCAN_OFF_MS) {
            scan();
        } else if (state == CONNECTING && t - since > 20000) {
            drop();
        }
    }

    var picking as Boolean = false;                  // the picker is open: keep scanning
    function setPicking(on as Boolean) as Void {
        picking = on;
        if (on && (state == RESTING || state == OFF)) { scan(); }
    }

    var regTimer as Timer.Timer? = null;
    function onRegTimeout() as Void {
        regTimer = null;
        if (registered || !supported) { return; }
        Log.p("no profile callback: scanning anyway");
        registered = true;
        scan();
    }

    // ---- delegate callbacks
    function onRegistered(ok as Boolean) as Void {
        Log.p("profile " + (ok ? "ok" : "FAIL") + " slots " + BluetoothLowEnergy.getAvailableConnectionCount() + " paired " + countOf(BluetoothLowEnergy.getPairedDevices()));
        if (registered) { return; }          // already scanning after the timeout
        registered = ok;
        if (ok) { scan(); } else { supported = false; }
    }

    function onScan(it as BluetoothLowEnergy.Iterator) as Void {
        var t = System.getTimer();
        for (var r = it.next() as BluetoothLowEnergy.ScanResult?; r != null; r = it.next() as BluetoothLowEnergy.ScanResult?) {
            var p = Wire.parseAdvert(r.getManufacturerSpecificData(Wire.COMPANY));
            if (p == null) { continue; }
            var id = p[0] as Number, found = false;
            for (var i = 0; i < boats.size(); i++) {
                var b = boats[i] as Array;
                if (b[0] == id) { b[1] = p[1]; b[2] = r.getRssi(); b[3] = r; b[4] = t; found = true; break; }
            }
            if (!found && boats.size() < MAX_BOATS) { boats.add([id, p[1], r.getRssi(), r, t]); Log.p("boat " + p[1] + " rssi " + r.getRssi()); }
            // link test build only: no boat yet, so take the first one found
            if (Model.boatId == null && Log.auto() && (state == SCANNING || state == RESTING)) {
                Log.p("auto: picking " + p[1]);
                Model.setBoat(id, p[1] as String);
                connect([id, p[1], r.getRssi(), r, t]);
                return;
            }
            // the remembered boat: connect straight away
            if (Model.boatId != null && id == Model.boatId && (state == SCANNING || state == RESTING)) {
                Model.boatName = p[1] as String;
                connect([id, p[1], r.getRssi(), r, t]);
                return;
            }
        }
        WatchUi.requestUpdate();
    }

    function countOf(it as BluetoothLowEnergy.Iterator?) as Number {
        var n = 0;
        if (it != null) { for (var d = it.next(); d != null; d = it.next()) { n++; } }
        return n;
    }

    // While connecting, check every second whether the device is already connected without a callback (for
    // example, if the watch reuses the link Gadgetbridge or Garmin Connect already has to this phone)
    var poll as Timer.Timer? = null;
    var pollN as Number = 0;
    function startPoll() as Void {
        stopPoll(); pollN = 0;
        poll = new Timer.Timer();
        (poll as Timer.Timer).start(new Lang.Method(Ble, :onPoll), 1000, true);
    }
    function stopPoll() as Void { if (poll != null) { (poll as Timer.Timer).stop(); poll = null; } }
    function onPoll() as Void {
        if (state != CONNECTING || device == null) { stopPoll(); return; }
        pollN++;
        var c = (device as BluetoothLowEnergy.Device).isConnected();
        if (pollN % 5 == 0) { Log.p("poll " + pollN + "s connected=" + c); }
        if (c) { Log.p("connected without callback"); stopPoll(); onConnected(device as BluetoothLowEnergy.Device, BluetoothLowEnergy.CONNECTION_STATE_CONNECTED); }
    }

    // User picked a boat in the list (LinkPage)
    function pick(i as Number) as Void {
        if (i < 0 || i >= boats.size()) { return; }
        var b = boats[i] as Array;
        Model.setBoat(b[0] as Number, b[1] as String);
        connect(b);
    }

    function connect(b as Array) as Void {
        try {
            BluetoothLowEnergy.setScanState(BluetoothLowEnergy.SCAN_STATE_OFF);
            target = b;
            Log.p("connect " + b[1]);
            Log.p("slots " + BluetoothLowEnergy.getAvailableConnectionCount() + " rssi " + b[2]);
            device = BluetoothLowEnergy.pairDevice(b[3] as BluetoothLowEnergy.ScanResult);
            Log.p("pairDevice " + (device == null ? "null" : "ok"));
            if (device == null) { drop(); return; }            // no free connection slot: back to scanning
            setState(CONNECTING);
            startPoll();
        } catch (e) {
            Log.p("pairDevice threw");
            drop();
        }
    }

    function onConnected(d as BluetoothLowEnergy.Device, st as BluetoothLowEnergy.ConnectionState) as Void {
        Log.p("conn state " + st);
        if (st == BluetoothLowEnergy.CONNECTION_STATE_CONNECTED) {
            stopPoll();
            var s = d.getService(BluetoothLowEnergy.stringToUuid(SVC));
            Log.p("service " + (s == null ? "MISSING" : "found") + " name " + d.getName());
            var tx = s == null ? null : s.getCharacteristic(BluetoothLowEnergy.stringToUuid(TX));
            rx = s == null ? null : s.getCharacteristic(BluetoothLowEnergy.stringToUuid(RX));
            var cccd = tx == null ? null : tx.getDescriptor(BluetoothLowEnergy.cccdUuid());
            if (cccd == null || rx == null) { Log.p("no TCS service"); drop(); return; }
            cccd.requestWrite([0x01, 0x00]b);           // notifications on; READY when the write completes
        } else {
            drop();
        }
    }

    function onNotifyOn(ok as Boolean) as Void {
        Log.p("notify " + (ok ? "on" : "FAIL"));
        if (!ok) { drop(); return; }
        queue = []; busy = false; reasm = new Wire.Reassembler();
        setState(READY);
        Link.onBleReady();
    }

    // Lost or failed connection: tidy up and go back to scanning
    function drop() as Void {
        var was = state;
        Log.p("drop from " + was);
        stopPoll();
        try { if (device != null) { BluetoothLowEnergy.unpairDevice(device as BluetoothLowEnergy.Device); } } catch (e) {}
        device = null; rx = null; queue = []; busy = false; target = null;
        if (was == READY) { Link.onBleDown(); }
        scan();
    }

    function onChanged(value as ByteArray) as Void {
        var m = reasm.push(value);
        if (m instanceof Array) { Link.onArray(m as Array); }
    }

    // ---- sending: one GATT write in flight at a time
    // Queues a whole message. Refuses it (false) rather than cutting chunks when the queue is full; reliable
    // messages then stay in Link's outbox and go on the next resend.
    function send(m as ByteArray) as Boolean {
        if (state != READY) { return false; }
        var chunks = Wire.frame(m, counter);
        if (queue.size() + chunks.size() > 120) { Log.p("queue full"); return false; }
        counter = (counter + 1) % 64;
        queue.addAll(chunks);
        pump();
        return true;
    }

    function pump() as Void {
        if (busy || queue.size() == 0 || rx == null) { return; }
        busy = true;
        try {
            (rx as BluetoothLowEnergy.Characteristic).requestWrite(queue[0], { :writeType => BluetoothLowEnergy.WRITE_TYPE_WITH_RESPONSE });
        } catch (e) {
            busy = false; queue = [];
        }
    }

    function onWritten(ok as Boolean) as Void {
        if (!ok) { Log.p("write FAIL"); }
        busy = false;
        if (queue.size() > 0) { queue = queue.slice(1, null); }
        // a failed chunk breaks that message; reliable messages are resent from the outbox
        pump();
    }

    function forget() as Void {
        Model.setBoat(null, null);
        Link.trusted = false;
        if (state == READY || state == CONNECTING) { drop(); } else { scan(); }
    }

    function ready() as Boolean { return state == READY; }
}

(:ble)
class TcsBleDelegate extends BluetoothLowEnergy.BleDelegate {
    function initialize() { BleDelegate.initialize(); }
    function onProfileRegister(uuid as BluetoothLowEnergy.Uuid, status as BluetoothLowEnergy.Status) as Void {
        Ble.onRegistered(status == BluetoothLowEnergy.STATUS_SUCCESS);
    }
    function onScanResults(scanResults as BluetoothLowEnergy.Iterator) as Void { Ble.onScan(scanResults); }
    function onConnectedStateChanged(device as BluetoothLowEnergy.Device, state as BluetoothLowEnergy.ConnectionState) as Void {
        Ble.onConnected(device, state);
    }
    function onDescriptorWrite(descriptor as BluetoothLowEnergy.Descriptor, status as BluetoothLowEnergy.Status) as Void {
        Ble.onNotifyOn(status == BluetoothLowEnergy.STATUS_SUCCESS);
    }
    function onCharacteristicWrite(characteristic as BluetoothLowEnergy.Characteristic, status as BluetoothLowEnergy.Status) as Void {
        Ble.onWritten(status == BluetoothLowEnergy.STATUS_SUCCESS);
    }
    function onCharacteristicChanged(characteristic as BluetoothLowEnergy.Characteristic, value as Lang.ByteArray) as Void {
        Ble.onChanged(value);
    }
}

// Devices without the Bluetooth API: the app runs without a phone link
(:noble)
module Ble {
    const OFF = 0; const SCANNING = 1; const RESTING = 2; const CONNECTING = 3; const READY = 4;
    var state as Number = OFF;
    var supported as Boolean = false;
    var boats as Array<Array> = [];
    function start() as Void {}
    function stop() as Void {}
    function tick() as Void {}
    function setPicking(on as Boolean) as Void {}
    function pick(i as Number) as Void {}
    function send(m as ByteArray) as Boolean { return false; }
    function forget() as Void { Model.setBoat(null, null); }
    function ready() as Boolean { return false; }
    function drop() as Void {}
}
