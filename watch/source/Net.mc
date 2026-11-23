import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;
import Toybox.Timer;
import Toybox.WatchUi;

// Gadgetbridge transport (PROTOCOL.md section 9). Watch -> phone: POST http://127.0.0.1:52741/tcs (52742 fallback),
// carried by Gadgetbridge + Internet Helper to TCS; the reply carries PAIR, ACKs and queued items. Phone -> watch:
// Gadgetbridge Intent API pushes, arriving as phone app messages {"m": [msg], "a": [a0, a1]}.
// One request in flight at a time. Works the same with Garmin Connect's message route for pushes.
module Net {
    const PORTS = [52741, 52742];
    const WAIT_POLL_MS = 3000;          // while waiting for Allow on the phone
    const KEEPALIVE_MS = 120000;        // at least one request every 2 min while open (section 9.4)
    const RETRY_MS = 10000;             // TCS not reachable: try again

    var supported as Boolean = false;
    var port as Number = 0;             // index into PORTS
    var queue as Array<Array> = [];     // messages waiting to go
    var busy as Boolean = false;
    var needPoll as Boolean = false;
    var lastReq as Number = -1000000;   // ms
    var lastOk as Number = -1000000;    // ms, last HTTP 200
    var fails as Number = 0;            // requests failed in a row
    var lastCode as Number = 0;
    var latency as Number = 0;          // ms, last successful round trip
    var canPush as Boolean = true;      // reply "p": false = TCS can't push to this watch, so poll (section 9.6)
    const NOPUSH_POLL_MS = 5000;
    var pollC as Number = 0;            // counter for empty polls (section 9.3 "c")

    function start() as Void {
        supported = (Toybox has :Communications) && (Communications has :makeWebRequest) && Link.canProve();
        if (!supported) { Log.p("net unsupported"); return; }
        if (Communications has :registerForPhoneAppMessages) {
            Communications.registerForPhoneAppMessages(new Lang.Method(Net, :onPush));
        }
        Link.hello();                   // the app's 1-second heartbeat calls onTimer()
    }

    // Best effort: BYE (113) so the phone stops pushing; the 5-minute timeout covers it if this doesn't arrive
    function stop() as Void {
        if (supported && Link.trusted) { queue = []; busy = false; Link.send(Link.BYE, []); }
    }

    function reachable() as Boolean { return fails < 2 && System.getTimer() - lastOk < 300000; }

    // Queue a message; a resend of one already waiting is ignored
    function send(msg as Array) as Void {
        if (!supported) { return; }
        for (var i = 0; i < queue.size(); i++) {
            var q = queue[i] as Array;
            if (q[2] == msg[2] && q[1] == msg[1]) { return; }
        }
        queue.add(msg);
        pump();
    }

    function poll() as Void { needPoll = true; pump(); }

    function onTimer() as Void {
        if (busy) { return; }
        var t = System.getTimer();
        if (!Link.trusted) {
            // waiting for Allow: keep asking every 3 s; TCS not answering: every 10 s
            var every = (Link.pairState == Link.PAIR_WAIT) ? WAIT_POLL_MS : RETRY_MS;
            if (t - lastReq >= every) { Link.hello(); }
        } else if (t - lastReq >= (canPush ? KEEPALIVE_MS : NOPUSH_POLL_MS)) {
            poll();
        }
    }

    function freeOfAuth(msgs as Array) as Boolean {
        if (msgs.size() == 0) { return false; }
        for (var i = 0; i < msgs.size(); i++) {
            var ty = (msgs[i] as Array)[1];
            if (ty != Link.HELLO && ty != Link.PROVE) { return false; }
        }
        return true;
    }

    function pump() as Void {
        if (busy || !supported) { return; }
        var signable = Link.trusted && Link.nonce != null;
        if (!signable) {
            // before PAIR 0 only HELLO / PROVE may go (unsigned); anything else waits (reliable ones are in the outbox)
            var keep = [] as Array<Array>;
            for (var i = 0; i < queue.size(); i++) {
                var ty = (queue[i] as Array)[1];
                if (ty == Link.HELLO || ty == Link.PROVE) { keep.add(queue[i] as Array); }
            }
            queue = keep;
            needPoll = false;
        }
        if (queue.size() == 0 && !needPoll) { return; }
        var n = queue.size() > 20 ? 20 : queue.size();
        var msgs = queue.slice(0, n);
        queue = queue.slice(n, null);
        needPoll = false;
        var body = { "w" => Model.watchId(), "m" => msgs, "a" => null };
        if (!freeOfAuth(msgs)) {
            var k = 0;
            if (msgs.size() > 0) { k = (msgs[0] as Array)[2] as Number; }
            else { pollC++; k = pollC; body["c"] = pollC; }
            body["a"] = Link.auth(k);
        }
        busy = true;
        lastReq = System.getTimer();
        Communications.makeWebRequest("http://127.0.0.1:" + PORTS[port] + "/tcs", body,
            { :method => Communications.HTTP_REQUEST_METHOD_POST,
              :headers => { "Content-Type" => Communications.REQUEST_CONTENT_TYPE_JSON },
              :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON },
            new Lang.Method(Net, :onReply));
    }

    function onReply(code as Number, data as Dictionary or String or Null) as Void {
        busy = false;
        lastCode = code;
        var t = System.getTimer();
        if (code == 200 && data instanceof Dictionary) {
            if (fails > 0 || t - lastOk > 60000) { Log.p("http ok " + (t - lastReq) + "ms port " + PORTS[port]); }
            fails = 0; lastOk = t; latency = t - lastReq;
            var m = (data as Dictionary)["m"];
            if (m instanceof Array) {
                for (var i = 0; i < (m as Array).size(); i++) {
                    var a = (m as Array)[i];
                    if (a instanceof Array) { Link.onArray(a as Array); }
                }
            }
            if ((data as Dictionary)["more"] == true) { needPoll = true; }
            var pv = (data as Dictionary)["p"];
            if (pv instanceof Boolean) { canPush = pv as Boolean; }
        } else if (code == 403) {
            Log.p("http 403: handshake again");
            Link.onRefused();
        } else {
            fails++;
            Log.p("http " + code + " (fail " + fails + ")");
            if (fails % 2 == 0) { port = 1 - port; }      // refused on one port: try the other
            if (fails >= 2) { Link.onUnreachable(); }
        }
        WatchUi.requestUpdate();
        pump();
    }

    // Gadgetbridge push: {"m": [msg] or msg, "a": [a0, a1]}. Dropped unless signed with this session's nonce.
    function onPush(msg as Communications.PhoneAppMessage) as Void {
        var d = msg.data;
        if (!(d instanceof Dictionary)) { Log.p("push ignored"); return; }
        var m = (d as Dictionary)["m"], a = (d as Dictionary)["a"];
        if (!(m instanceof Array) || (m as Array).size() == 0) { return; }
        var list = ((m as Array)[0] instanceof Array) ? m as Array : [m];
        for (var i = 0; i < list.size(); i++) {
            var one = list[i];
            if (!(one instanceof Array) || (one as Array).size() < 3) { continue; }
            var s = (one as Array)[2];
            if (!Link.trusted || !(s instanceof Number) || !Link.authOk(a, s as Number)) { Log.p("push unsigned, dropped"); continue; }
            if ((one as Array)[1] == Link.PULL) { poll(); continue; }
            Link.onArray(one as Array);
        }
    }
}
