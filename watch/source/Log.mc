import Toybox.Application;
import Toybox.Communications;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;

// Link test log and automation (scripts/linktest.ps1). Only the link test build compiles the real one (annotation
// :log); every other build gets the empty stub. On the watch the lines go to GARMIN\APPS\LOGS\TCS.TXT when that file
// exists. In the test build the watch also picks the first TCS boat by itself and, once paired, tours the pages
// (20 s each) and forces one reconnect, so the test runs with nobody touching the watch after opening the app.
(:log)
module Log {
    var step as Number = 0;
    var stepAt as Number = 0;
    var lines as Array<String> = [];          // last lines, also kept in Storage and shown on the Boat link screen

    // Log a line: to TCS.TXT (if the file exists), to Storage and to the on-screen list, in case the file fails
    function p(s as String) as Void {
        var line = (System.getTimer() / 1000 % 10000).format("%04d") + " " + s;
        System.println(line);
        lines.add(line);
        while (lines.size() > 14) { lines = lines.slice(1, null); }
        Application.Storage.setValue("dbg", lines);
    }
    function start() as Void {
        var old = Application.Storage.getValue("dbg");
        if (old instanceof Array && old.size() > 0) { System.println("previous run: " + (old as Array)[old.size() - 1]); }
        lines = [];
        p("app start");
    }

    // Gadgetbridge transport probe (PROTOCOL 9.5): does a plain http://127.0.0.1 request get through, and do
    // Gadgetbridge Intent API pushes arrive? Results go to the log.
    var probeN as Number = 0;
    var probeAt as Number = 0;
    function probe() as Void {
        if (Communications has :registerForPhoneAppMessages) {
            Communications.registerForPhoneAppMessages(new Lang.Method(Log, :onPush));
            p("push listener on");
        }
        probeReq();
    }
    function probeReq() as Void {
        probeN++;
        probeAt = System.getTimer();
        p("http try " + probeN);
        Communications.makeWebRequest("http://127.0.0.1:52741/tcs",
            { "w" => Model.watchId(), "a" => null, "m" => [[1, 101, 0, 1, "probe", 0, Model.now(), Model.watchId(), false, null]] },
            { :method => Communications.HTTP_REQUEST_METHOD_POST,
              :headers => { "Content-Type" => Communications.REQUEST_CONTENT_TYPE_JSON },
              :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON },
            new Lang.Method(Log, :onReply));
    }
    function onReply(code as Number, data as Dictionary or String or Null) as Void {
        p("http " + code + " in " + (System.getTimer() - probeAt) + "ms " + (data == null ? "" : data.toString()));
        if (probeN < 3) { probeReq(); }
    }
    function onPush(msg as Communications.PhoneAppMessage) as Void {
        p("push " + (msg.data == null ? "null" : msg.data.toString()));
    }
    // Link test build: the log on the Boat link screen (small, below everything else)
    function draw(dc as Graphics.Dc, f as Draw.Frame) as Void {
        dc.setColor(Theme.MIST, Graphics.COLOR_TRANSPARENT);
        var y = f.y(214), n = lines.size(), k = n > 8 ? n - 8 : 0;
        for (var i = k; i < n; i++) {
            dc.drawText(f.x(70), y, Graphics.FONT_XTINY, lines[i], Graphics.TEXT_JUSTIFY_LEFT);
            y += f.d(18);
        }
    }
    function auto() as Boolean { return false; }     // the automatic page tour is gone (it confused testing)
}

(:nolog)
module Log {
    function p(s as String) as Void {}
    function start() as Void {}
    function draw(dc as Graphics.Dc, f as Draw.Frame) as Void {}
    function auto() as Boolean { return false; }
}
