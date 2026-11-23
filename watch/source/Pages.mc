import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.Timer;
import Toybox.WatchUi;

// Page framework. The main loop (Gauge, Next mark, Anchor, Conditions, [Sight], MOB) and pushed screens (Set
// anchor, angle entry, shot review) are all Page objects shown by a Host view. One delegate maps touch and buttons:
//   swipe / Up / Down = pages (MOB is one step "previous" from the Gauge), Start or tap = select,
//   touch hold or Menu = the page's hold action, drag = drag (CIQ 3.3+), Back = Gauge, then exit.

class Page {
    var host as Host? = null;
    function initialize() {}
    function id() as Number { return -1; }
    function draw(dc as Dc, f as Draw.Frame) as Void {}
    function tap(f as Draw.Frame, x as Number, y as Number) as Boolean { return false; }
    function select() as Boolean { return false; }
    // Touch hold began at (x, y): return the hold length in ms to start one, or 0
    function holdAt(f as Draw.Frame, x as Number, y as Number) as Number { return 0; }
    function holdMs() as Number { return 0; }           // > 0: the Menu button runs holdDone() at once
    function menu() as Boolean { if (holdMs() > 0) { holdDone(); return true; } return false; }
    function holdDone() as Void {}
    function keyHoldMs() as Number { return 0; }                // buttons: hold Start this long (MOB reset)
    function keyDown() as Void {}                               // Start went down on a page with a Start hold
    function showIcon() as Boolean { return true; }             // the phone link icon (LinkUi), ring at 7 o'clock
    function drawTop(dc as Dc, f as Draw.Frame) as Void {}      // drawn over the icon: things that must never hide
    function dragStart(f as Draw.Frame, x as Number, y as Number) as Void {}
    function drag(f as Draw.Frame, x as Number, y as Number) as Void {}
    function dragEnd(f as Draw.Frame, x as Number, y as Number) as Void {}
    function upDown(dir as Number) as Boolean { return false; }  // pushed screens use Up/Down for drums
    function wantsDrag() as Boolean { return false; }           // only screens that drag; others keep swipes
    function gps() as Boolean { return false; }
    function tickMs() as Number { return 0; }
    function tick() as Void {}
    function redrawOnTick() as Boolean { return true; }
    function shown() as Void {}
    function gone() as Void {}
    function update() as Void { WatchUi.requestUpdate(); }
}

class Host extends WatchUi.View {
    var pages as Array<Page>;
    var index as Number = 0;
    var isLoop as Boolean;
    // One Timer per Host, created once and reused: it drives the page's tick and the hold animation. The watch
    // limits how many timers an app may run ("Too Many Timers" crash on the vivoactive 5).
    var timer as Timer.Timer;
    var running as Number = 0;          // period the timer runs at now (0 = stopped)
    var lastTick as Number = 0;
    var holdStart as Number = 0;
    var holdLen as Number = 0;
    var frame as Draw.Frame? = null;
    var lastDrag as Number = -10000;

    function initialize(list as Array<Page>, loop as Boolean) {
        View.initialize();
        timer = new Timer.Timer();
        pages = list;
        isLoop = loop;
        for (var i = 0; i < pages.size(); i++) { pages[i].host = self; }
    }

    function page() as Page { return pages[index]; }

    function onShow() as Void { enter(); }
    function onHide() as Void { leave(); }

    function enter() as Void {
        var p = page();
        p.shown();
        Sensors.setGps(p.gps() || Model.mobOn);
        if (p.id() >= 0) { Link.page(p.id()); }
        restartTimer();
    }

    function leave() as Void {
        stopTimer();
        holdCancel();
        page().gone();
    }

    // Run the one timer at the fastest rate needed: 50 ms during a hold, else the page's own tick
    function restartTimer() as Void {
        var ms = holdLen > 0 ? 50 : page().tickMs();
        if (ms == running) { return; }
        timer.stop();
        running = ms;
        if (ms > 0) { lastTick = System.getTimer(); timer.start(method(:onTick), ms, true); }
    }
    function stopTimer() as Void { timer.stop(); running = 0; }
    function onTick() as Void {
        var t = System.getTimer();
        if (holdLen > 0) {
            WatchUi.requestUpdate();
            if (holdFrac() >= 1.0) { holdCancel(); page().holdDone(); return; }
        }
        var p = page(), pm = p.tickMs();
        if (pm == 0 && holdLen == 0) { stopTimer(); return; }      // nothing left to tick: save battery
        if (pm > 0 && t - lastTick >= pm - 10) {
            lastTick = t;
            p.tick();
            if (p.redrawOnTick()) { WatchUi.requestUpdate(); }
        }
    }

    // Swap the loop's pages (Sight on/off) keeping the current page if it still exists
    function setPages(list as Array<Page>) as Void {
        var cur = page();
        leave();
        pages = list;
        for (var i = 0; i < pages.size(); i++) { pages[i].host = self; }
        index = pages.indexOf(cur);
        if (index < 0) { index = 0; }
        enter();
        WatchUi.requestUpdate();
    }

    function move(dir as Number) as Void {
        if (!isLoop) { return; }
        leave();
        index = (index + dir + pages.size()) % pages.size();
        enter();
        WatchUi.requestUpdate();
    }

    function goTo(pageId as Number) as Void {
        for (var i = 0; i < pages.size(); i++) {
            if (pages[i].id() == pageId && i != index) { leave(); index = i; enter(); WatchUi.requestUpdate(); return; }
        }
    }

    // ---- holds (touch hold or Menu)
    function holdBegin(ms as Number) as Void {
        if (ms <= 0) { return; }
        holdStart = System.getTimer();
        holdLen = ms;
        restartTimer();
    }
    function holdCancel() as Void {
        if (holdLen == 0) { return; }
        holdLen = 0;
        restartTimer();
        WatchUi.requestUpdate();
    }
    function holdFrac() as Float {
        if (holdLen <= 0) { return 0.0; }
        var fr = (System.getTimer() - holdStart).toFloat() / holdLen;
        return fr > 1.0 ? 1.0 : fr;
    }
    function onUpdate(dc as Dc) as Void {
        var f = new Draw.Frame(dc);
        frame = f;
        Draw.clear(dc);
        Draw.aa(dc);
        page().draw(dc, f);
        if (page().showIcon()) { LinkUi.drawIcon(dc, f); }
        page().drawTop(dc, f);
    }
}

class HostDelegate extends WatchUi.BehaviorDelegate {
    var host as Host;
    // Pages with a Start hold (keyHoldMs > 0) get their Start "press" on release, so starting a hold never also
    // fires the press action. onSelect may arrive before or after the key events depending on the watch.
    var keyArmed as Boolean = false;
    var keyAt as Number = 0;
    var releasedAt as Number = -10000;

    function initialize(h as Host) {
        BehaviorDelegate.initialize();
        host = h;
    }

    function f() as Draw.Frame? { return host.frame; }

    function dragging() as Boolean { return host.page().wantsDrag() && System.getTimer() - host.lastDrag < 500; }

    function onNextPage() as Boolean {
        if (dragging()) { return true; }
        if (host.page().upDown(1)) { WatchUi.requestUpdate(); return true; }
        host.move(1); return true;
    }
    function onPreviousPage() as Boolean {
        if (dragging()) { return true; }
        if (host.page().upDown(-1)) { WatchUi.requestUpdate(); return true; }
        host.move(-1); return true;
    }

    function onSelect() as Boolean {
        Log.p("select");
        var t = System.getTimer();
        if (keyArmed && t - keyAt < 3000) { return true; }      // the release decides: press or hold
        keyArmed = false;
        if (t - releasedAt < 600) { return true; }              // the release already ran select()
        host.page().select(); WatchUi.requestUpdate(); return true;
    }

    function onTap(evt as WatchUi.ClickEvent) as Boolean {
        var fr = f();
        if (fr == null) { return false; }
        var c = evt.getCoordinates();
        Log.p("tap " + c[0] + "," + c[1] + (LinkUi.iconHit(fr, c[0], c[1]) ? " icon" : ""));
        if (host.page().showIcon() && LinkUi.iconHit(fr, c[0], c[1])) { Ui.push(new LinkPage()); return true; }
        var used = host.page().tap(fr, c[0], c[1]);
        WatchUi.requestUpdate();
        return used;            // not used -> BehaviorDelegate turns the tap into onSelect
    }

    function onHold(evt as WatchUi.ClickEvent) as Boolean {
        var fr = f();
        if (fr == null) { return false; }
        var c = evt.getCoordinates();
        Log.p("hold " + c[0] + "," + c[1]);
        var ms = host.page().holdAt(fr, c[0], c[1]);
        if (ms > 0) { host.holdBegin(ms); return true; }
        return false;
    }

    function onRelease(evt as WatchUi.ClickEvent) as Boolean {
        if (host.holdLen > 0 && host.holdFrac() < 1.0) { host.holdCancel(); }
        return true;
    }

    function onKeyPressed(evt as WatchUi.KeyEvent) as Boolean {
        Log.p("key " + evt.getKey());
        if (evt.getKey() == WatchUi.KEY_ENTER) {
            var ms = host.page().keyHoldMs();
            if (ms > 0) {
                host.page().keyDown();
                keyArmed = true; keyAt = System.getTimer();
                host.holdBegin(ms);
                return true;
            }
        }
        return false;
    }

    function onKeyReleased(evt as WatchUi.KeyEvent) as Boolean {
        if (evt.getKey() != WatchUi.KEY_ENTER || !keyArmed) { return false; }
        keyArmed = false;
        if (host.holdLen > 0 && host.holdFrac() < 1.0) {        // released early: it was a press
            host.holdCancel();
            host.page().select();
            releasedAt = System.getTimer();
            WatchUi.requestUpdate();
        }
        return true;
    }

    function onMenu() as Boolean { var used = host.page().menu(); WatchUi.requestUpdate(); return used; }

    function onBack() as Boolean {
        Log.p("key back");
        if (!host.isLoop) { WatchUi.popView(WatchUi.SLIDE_DOWN); return true; }
        if (host.index != 0) { host.move(-host.index); return true; }
        return false;           // on the Gauge: leave the app
    }
}

// Pages that drag (Set anchor ring, dials, sextant drums) get this delegate; every other page gets the plain one,
// because handling drags can stop taps from arriving on some watches.
class DragDelegate extends HostDelegate {
    function initialize(h as Host) { HostDelegate.initialize(h); }

    // Drag events need CIQ 3.3; older touch devices simply never call this
    function onDrag(evt as WatchUi.DragEvent) as Boolean {
        var fr = f();
        if (fr == null || !host.page().wantsDrag()) { return false; }
        var c = evt.getCoordinates();
        var t = evt.getType();
        host.lastDrag = System.getTimer();
        if (t == WatchUi.DRAG_TYPE_START) { host.page().dragStart(fr, c[0], c[1]); }
        else if (t == WatchUi.DRAG_TYPE_CONTINUE) { host.page().drag(fr, c[0], c[1]); }
        else { host.page().dragEnd(fr, c[0], c[1]); }
        WatchUi.requestUpdate();
        return true;
    }

}

module Ui {
    function push(p as Page) as Void {
        var h = new Host([p], false);
        WatchUi.pushView(h, p.wantsDrag() ? new DragDelegate(h) : new HostDelegate(h), WatchUi.SLIDE_UP);
    }
    function pop() as Void { WatchUi.popView(WatchUi.SLIDE_DOWN); }
}
