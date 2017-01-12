/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import static haven.MCache.cmaps;
import static haven.MCache.tilesz;

import java.awt.Color;
import java.awt.event.KeyEvent;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.function.Predicate;

import haven.BuddyWnd.GroupSelector;
import haven.MapFile.Marker;
import haven.MapFile.PMarker;
import haven.MapFile.SMarker;
import haven.MapFileWidget.Locator;
import haven.MapFileWidget.MapLocator;
import haven.MapFileWidget.SpecLocator;

public class MapWnd extends Window {
    public static final Resource markcurs = Resource.local().loadwait("gfx/hud/curs/flag");
    public final MapFileWidget view;
    public final MapView mv;
    public final MarkerList list;
    private final Locator player;
    private final Widget toolbar;
    private final Frame viewf, listf;
    private final Button pmbtn, smbtn;
    private TextEntry namesel;
    private GroupSelector colsel;
    private Button mremove;
    private Predicate<Marker> mflt = pmarkers;
    private Comparator<Marker> mcmp = namecmp;
    private List<Marker> markers = Collections.emptyList();
    private int markerseq = -1;
    private boolean domark = false;
    private final Collection<Runnable> deferred = new LinkedList<>();
    private static final Tex plx = Text.renderstroked("\u2716",  Color.red, Color.BLACK, LocalMiniMap.bld12fnd).tex();

    private final static Predicate<Marker> pmarkers = (m -> m instanceof PMarker);
    private final static Predicate<Marker> smarkers = (m -> m instanceof SMarker);
    private final static Comparator<Marker> namecmp = ((a, b) -> a.nm.compareTo(b.nm));

    public MapWnd(MapFile file, MapView mv, Coord sz, String title) {
        super(sz, title, true);
        this.mv = mv;
        this.player = new MapLocator(mv);
        viewf = add(new Frame(Coord.z, true));
        view = viewf.add(new View(file));
        recenter();
        toolbar = add(new Widget(Coord.z));
        toolbar.add(new Img(Resource.loadtex("gfx/hud/mmap/fgwdg")), Coord.z);
        toolbar.add(new IButton("gfx/hud/mmap/home", "", "-d", "-h") {
            {
                tooltip = RichText.render("Follow ($col[255,255,0]{Home})", 0);
            }

            public void click() {
                recenter();
            }
        }, Coord.z);
        toolbar.add(new IButton("gfx/hud/mmap/mark", "", "-d", "-h") {
            {
                tooltip = RichText.render("Add marker", 0);
            }

            public void click() {
                domark = true;
            }
        }, Coord.z);
        toolbar.pack();
        listf = add(new Frame(new Coord(200, 200), false));
        list = listf.add(new MarkerList(listf.inner().x, 0));
        pmbtn = add(new Button(95, "Placed", false) {
            public void click() {
                mflt = pmarkers;
                markerseq = -1;
            }
        });
        smbtn = add(new Button(95, "Natural", false) {
            public void click() {
                mflt = smarkers;
                markerseq = -1;
            }
        });
        resize(sz);
    }

    private class View extends MapFileWidget {
        View(MapFile file) {
            super(file, Coord.z);
        }

        public boolean clickmarker(DisplayMarker mark, int button) {
            if (button == 1) {
                list.change2(mark.m);
                list.display(mark.m);
                return (true);
            }
            return (false);
        }

        public boolean clickloc(Location loc, int button) {
            if (domark && (button == 1)) {
                Marker nm = new PMarker(loc.seg.id, loc.tc, "New marker", BuddyWnd.gc[new Random().nextInt(BuddyWnd.gc.length)]);
                file.add(nm);
                list.change2(nm);
                list.display(nm);
                domark = false;
                return (true);
            }
            return (false);
        }

        public boolean mousedown(Coord c, int button) {
            if (domark && (button == 3)) {
                domark = false;
                return (true);
            }
            return (super.mousedown(c, button));
        }

        public void draw(GOut g) {
            g.chcolor(0, 0, 0, 128);
            g.frect(Coord.z, sz);
            g.chcolor();
            super.draw(g);
            try {
                Coord ploc = xlate(resolve(player));
                if (ploc != null) {
                    g.chcolor(255, 0, 0, 255);
                    g.image(plx, ploc.sub(plx.sz().div(2)));
                    g.chcolor();
                }
            } catch (Loading l) {
            }
        }

        public Resource getcurs(Coord c) {
            if (domark)
                return (markcurs);
            return (super.getcurs(c));
        }
    }

    public void tick(double dt) {
        try {
			super.tick(dt);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
        synchronized (deferred) {
            for (Iterator<Runnable> i = deferred.iterator(); i.hasNext(); ) {
                Runnable task = i.next();
                try {
                    task.run();
                } catch (Loading l) {
                    continue;
                }
                i.remove();
            }
        }
        if (visible && (markerseq != view.file.markerseq)) {
            if (view.file.lock.readLock().tryLock()) {
                try {
                    List<Marker> markers = view.file.markers.stream().filter(mflt).collect(java.util.stream.Collectors.toList());
                    markers.sort(mcmp);
                    this.markers = markers;
                } finally {
                    view.file.lock.readLock().unlock();
                }
            }
        }
    }

    public static final Color every = new Color(255, 255, 255, 16), other = new Color(255, 255, 255, 32);

    public class MarkerList extends Listbox<Marker> {
        private final Text.Foundry fnd = CharWnd.attrf;

        public Marker listitem(int idx) {
            return (markers.get(idx));
        }

        public int listitems() {
            return (markers.size());
        }

        public MarkerList(int w, int n) {
            super(w, n, 20);
        }

        private Function<String, Text> names = new CachedFunction<>(500, nm -> fnd.render(nm));

        protected void drawbg(GOut g) {
        }

        public void drawitem(GOut g, Marker mark, int idx) {
            g.chcolor(((idx % 2) == 0) ? every : other);
            g.frect(Coord.z, g.sz);
            if (mark instanceof PMarker)
                g.chcolor(((PMarker) mark).color);
            else
                g.chcolor();
            g.aimage(names.apply(mark.nm).tex(), new Coord(5, itemh / 2), 0, 0.5);
        }

        public void change(Marker mark) {
            change2(mark);
            if (mark != null)
                view.center(new SpecLocator(mark.seg, mark.tc));
        }

        public void change2(Marker mark) {
            this.sel = mark;

            if (namesel != null) {
                ui.destroy(namesel);
                namesel = null;
                if (colsel != null) {
                    ui.destroy(colsel);
                    colsel = null;
                    ui.destroy(mremove);
                    mremove = null;
                }
            }

            if (mark != null) {
                if (namesel == null) {
                    namesel = MapWnd.this.add(new TextEntry(200, "") {
                        {
                            dshow = true;
                        }

                        public void activate(String text) {
                            mark.nm = text;
                            view.file.update(mark);
                            commit();
                        }
                    });
                }
                namesel.settext(mark.nm);
                namesel.buf.point = mark.nm.length();
                namesel.commit();
                if (mark instanceof PMarker) {
                    PMarker pm = (PMarker) mark;
                    colsel = MapWnd.this.add(new GroupSelector(0) {
                        public void changed(int group) {
                            this.group = group;
                            pm.color = BuddyWnd.gc[group];
                            view.file.update(mark);
                        }
                    });
                    if ((colsel.group = Utils.index(BuddyWnd.gc, pm.color)) < 0)
                        colsel.group = 0;
                    mremove = MapWnd.this.add(new Button(200, "Remove", false) {
                        public void click() {
                            view.file.remove(mark);
                            change2(null);
                        }
                    });
                }
                MapWnd.this.resize(asz);
            }
        }
    }

    public void resize(Coord sz) {
        super.resize(sz);
        listf.resize(listf.sz.x, sz.y - 120);
        listf.c = new Coord(sz.x - listf.sz.x, 0);
        list.resize(listf.inner());
        pmbtn.c = new Coord(sz.x - 200, sz.y - pmbtn.sz.y);
        smbtn.c = new Coord(sz.x - 95, sz.y - smbtn.sz.y);
        if (namesel != null) {
            namesel.c = listf.c.add(0, listf.sz.y + 10);
            if (colsel != null) {
                colsel.c = namesel.c.add(0, namesel.sz.y + 10);
                mremove.c = colsel.c.add(0, colsel.sz.y + 10);
            }
        }
        viewf.resize(new Coord(sz.x - listf.sz.x - 10, sz.y));
        view.resize(viewf.inner());
        toolbar.c = viewf.c.add(0, viewf.sz.y - toolbar.sz.y).add(2, -2);
    }

    public void recenter() {
        view.follow(player);
    }

    private static final Tex sizer = Resource.loadtex("gfx/hud/wnd/sizer");

    protected void drawframe(GOut g) {
        g.image(sizer, ctl.add(csz).sub(sizer.sz()));
        super.drawframe(g);
    }

    public boolean keydown(KeyEvent ev) {
        if (ev.getKeyCode() == KeyEvent.VK_HOME) {
            recenter();
            return (true);
        }
        return (super.keydown(ev));
    }

    private UI.Grab drag;
    private Coord dragc;

    public boolean mousedown(Coord c, int button) {
        Coord cc = c.sub(ctl);
        if ((button == 1) && (cc.x < csz.x) && (cc.y < csz.y) && (cc.y >= csz.y - 25 + (csz.x - cc.x))) {
            if (drag == null) {
                drag = ui.grabmouse(this);
                dragc = asz.sub(c);
                return (true);
            }
        }
        return (super.mousedown(c, button));
    }

    public void mousemove(Coord c) {
        if (drag != null) {
            Coord nsz = c.add(dragc);
            nsz.x = Math.max(nsz.x, 300);
            nsz.y = Math.max(nsz.y, 150);
            resize(nsz);
        }
        super.mousemove(c);
    }

    public boolean mouseup(Coord c, int button) {
        if ((button == 1) && (drag != null)) {
            drag.remove();
            drag = null;
            return (true);
        }
        return (super.mouseup(c, button));
    }

    public void markobj(long gobid, long oid, Indir<Resource> resid, String nm) {
        synchronized (deferred) {
            deferred.add(new Runnable() {
                long f = 0;

                public void run() {
                    Resource res = resid.get();
                    String rnm = nm;
                    if (rnm == null) {
                        Resource.Tooltip tt = res.layer(Resource.tooltip);
                        if (tt == null)
                            return;
                        rnm = tt.t;
                    }
                    long now = System.currentTimeMillis();
                    if (f == 0)
                        f = now;
                    Gob gob = ui.sess.glob.oc.getgob(gobid);
                    if (gob == null) {
                        if (now - f < 1000)
                            throw (new Loading());
                        return;
                    }
                    Coord tc = gob.rc.floor(tilesz);
                    MCache.Grid obg = ui.sess.glob.map.getgrid(tc.div(cmaps));
                    if (!view.file.lock.writeLock().tryLock())
                        throw (new Loading());
                    try {
                        MapFile.GridInfo info = view.file.gridinfo.get(obg.id);
                        if (info == null)
                            throw (new Loading());
                        Coord sc = tc.add(info.sc.sub(obg.gc).mul(cmaps));
                        SMarker prev = view.file.smarkers.get(oid);
                        if (prev == null) {
                            view.file.add(new SMarker(info.seg, sc, rnm, oid, new Resource.Spec(Resource.remote(), res.name, res.ver)));
                        } else {
                            if ((prev.seg != info.seg) || !prev.tc.equals(sc)) {
                                prev.seg = info.seg;
                                prev.tc = sc;
                                view.file.update(prev);
                            }
                        }
                    } finally {
                        view.file.lock.writeLock().unlock();
                    }
                }
            });
        }
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
        if (sender == cbtn)
            show(false);
        else
            super.wdgmsg(sender, msg, args);
    }

    @Override
    public boolean type(char key, KeyEvent ev) {
        if (key == 27) {
            if (cbtn.visible)
                show(false);
            return true;
        }
        return super.type(key, ev);
    }
}
