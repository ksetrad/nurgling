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

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.*;
import haven.render.*;
import nurgling.*;

public class Gob extends NGob implements RenderTree.Node, Sprite.Owner, Skeleton.ModOwner, EquipTarget, Skeleton.HasPose {
    public Coord2d rc;
    public double a;
    public boolean virtual = false;
    int clprio = 0;
    public long id;
    public boolean removed = false;
    public final Glob glob;
    public Map<Class<? extends GAttrib>, GAttrib> attr = new HashMap<Class<? extends GAttrib>, GAttrib>();
    public final Collection<Overlay> ols = new ArrayList<>();
    public final Collection<RenderTree.Slot> slots = new ArrayList<>(1);
    public int updateseq = 0;
    private final Collection<SetupMod> setupmods = new ArrayList<>();
    private final LinkedList<Runnable> deferred = new LinkedList<>();
    private Loader.Future<?> deferral = null;

	public static class Overlay implements RenderTree.Node {
	public final int id;
	public final Gob gob;
	public final Indir<Resource> res;
	public MessageBuf sdt;
	public Sprite spr;
	public boolean delign = false;
	private Collection<RenderTree.Slot> slots = null;
	private boolean added = false;

	public Overlay(Gob gob, int id, Indir<Resource> res, Message sdt) {
		    this.gob = gob;
	    this.id = id;
	    this.res = res;
	    this.sdt = new MessageBuf(sdt);
	    this.spr = null;
	}

	public Overlay(Gob gob, Sprite spr) {
	    this.gob = gob;
	    this.id = -1;
	    this.res = null;
	    this.sdt = null;
	    this.spr = spr;
	}

	public Overlay(Gob gob, Sprite spr, int id) {
		this.gob = gob;
		this.id = id;
		this.res = null;
		this.sdt = null;
		this.spr = spr;
	}

	private void init() {
	    if(spr == null) {
		spr = Sprite.create(gob, res.get(), sdt);
		if(added && (spr instanceof SetupMod))
		    gob.setupmods.add((SetupMod)spr);
	    }
	    if(slots == null)
		RUtils.multiadd(gob.slots, this);
	}

	private void add0() {
	    if(added)
		throw(new IllegalStateException());
	    if(spr instanceof SetupMod)
		gob.setupmods.add((SetupMod)spr);
	    added = true;
	}

	private void remove0() {
	    if(!added)
		throw(new IllegalStateException());
	    if(slots != null) {
		RUtils.multirem(new ArrayList<>(slots));
		slots = null;
	    }
	    if(spr instanceof SetupMod)
		gob.setupmods.remove(spr);
	    added = false;
	}

	public void remove(boolean async) {
	    if(async) {
		gob.defer(() -> remove(false));
		return;
	    }
	    remove0();
	    if(gob.ols.remove(this))
			gob.checkol(this, false);
	}

	public void remove() {
	    remove(true);
	}

	public void added(RenderTree.Slot slot) {
	    slot.add(spr);
	    if(slots == null)
		slots = new ArrayList<>(1);
	    slots.add(slot);
	}

	public void removed(RenderTree.Slot slot) {
	    if(slots != null)
		slots.remove(slot);
	}
    }

    public static interface SetupMod {
	public default Pipe.Op gobstate() {return(null);}
	public default Pipe.Op placestate() {return(null);}
    }

    public Gob(Glob glob, Coord2d c, long id) {
	this.glob = glob;
	this.rc = c;
	this.id = id;
	if(id < 0)
	    virtual = true;
	else
		updwait(this::installTags, waiting -> {});

    }

	public void installTags(){
		installTags(getres());
	}

    public Gob(Glob glob, Coord2d c) {
	this(glob, c, -1);
    }

    public void ctick(double dt) {
	for(GAttrib a : attr.values())
	    a.ctick(dt);
	for(Iterator<Overlay> i = ols.iterator(); i.hasNext();) {
	    Overlay ol = i.next();
	    if(ol.slots == null) {
		try {
		    ol.init();
		} catch(Loading e) {}
	    } else {
		boolean done = ol.spr.tick(dt);
		if((!ol.delign || (ol.spr instanceof Sprite.CDel)) && done) {
		    ol.remove0();
		    i.remove();
		}
	    }
	}
	updstate();
	if(virtual && ols.isEmpty() && (getattr(Drawable.class) == null))
	    glob.oc.remove(this);
	updateCustom();
    }

    public void gtick(Render g) {
	Drawable d = getattr(Drawable.class);
	if(d != null)
	    d.gtick(g);
	for(Overlay ol : ols) {
	    if(ol.spr != null)
		ol.spr.gtick(g);
	}
    }

    void removed() {
	removed = true;
    }

    private void deferred() {
	while(true) {
	    Runnable task;
	    synchronized(deferred) {
		task = deferred.peek();
		if(task == null) {
		    deferral = null;
		    return;
		}
	    }
	    synchronized(this) {
		if(!removed)
		    task.run();
	    }
	    if(task instanceof Disposable)
		((Disposable)task).dispose();
	    synchronized(deferred) {
		if(deferred.poll() != task)
		    throw(new RuntimeException());
	    }
	}
    }

    public void defer(Runnable task) {
	synchronized(deferred) {
	    deferred.add(task);
	    if(deferral == null)
		deferral = glob.loader.defer(this::deferred, null);
	}
    }

    public void addol(Overlay ol, boolean async) {
	if(async) {
	    defer(() -> addol(ol, false));
	    return;
	}
	ol.init();
	ol.add0();
	if(ols.add(ol))
		checkol( ol, true);
    }
    public void addol(Overlay ol) {
	addol(ol, true);
    }

	public void findoraddol(Overlay ol) {
		if(findol(ol.spr.getClass())==null)
			synchronized (ols) {
				addol(ol, true);
			}
	}
	public void findoraddol(Sprite ol) {
		findoraddol(new Overlay(this, ol));
	}
    public void addol(Sprite ol) {
	addol(new Overlay(this, ol));
    }
    public void addol(Indir<Resource> res, Message sdt) {
	addol(new Overlay(this, -1, res, sdt));
    }

    public Overlay findol(int id) {
	for(Overlay ol : ols) {
	    if(ol.id == id)
		return(ol);
	}
	return(null);
    }

    public void dispose() {
	for(GAttrib a : attr.values())
	    a.dispose();
    }

    public void move(Coord2d c, double a) {
	Moving m = getattr(Moving.class);
	if(m != null)
	    m.move(c);
	this.rc = c;
	this.a = a;
    }

    public Coord3f getc() {
	Moving m = getattr(Moving.class);
	Coord3f ret = (m != null)?m.getc():getrc();
	DrawOffset df = getattr(DrawOffset.class);
	if(df != null)
	    ret = ret.add(df.off);
	return(ret);
    }

    public Coord3f getrc() {
	return(glob.map.getzp(rc));
    }

    protected Pipe.Op getmapstate(Coord3f pc) {
	Tiler tile = glob.map.tiler(glob.map.gettile(new Coord2d(pc).floor(MCache.tilesz)));
	return(tile.drawstate(glob, pc));
    }

    private Class<? extends GAttrib> attrclass(Class<? extends GAttrib> cl) {
	while(true) {
	    Class<?> p = cl.getSuperclass();
	    if(p == GAttrib.class)
		return(cl);
	    cl = p.asSubclass(GAttrib.class);
	}
    }

    public <C extends GAttrib> C getattr(Class<C> c) {
	GAttrib attr = this.attr.get(attrclass(c));
	if(!c.isInstance(attr))
	    return(null);
	return(c.cast(attr));
    }

    private void setattr(Class<? extends GAttrib> ac, GAttrib a) {
	GAttrib prev = attr.remove(ac);
	checkattr(this, ac, a, prev);
	if(prev != null) {
	    if((prev instanceof RenderTree.Node) && (prev.slots != null))
		RUtils.multirem(new ArrayList<>(prev.slots));
	    if(prev instanceof SetupMod)
		setupmods.remove(prev);
	}
	if(a != null) {
	    if(a instanceof RenderTree.Node) {
		try {
		    RUtils.multiadd(this.slots, (RenderTree.Node)a);
		} catch(Loading l) {
		    if(prev instanceof RenderTree.Node) {
			RUtils.multiadd(this.slots, (RenderTree.Node)prev);
			attr.put(ac, prev);
		    }
		    if(prev instanceof SetupMod)
			setupmods.add((SetupMod)prev);
		    throw(l);
		}
	    }
	    if(a instanceof SetupMod)
		setupmods.add((SetupMod)a);
	    attr.put(ac, a);
	}
	if(prev != null)
	    prev.dispose();
    }

    public void setattr(GAttrib a) {
	setattr(attrclass(a.getClass()), a);
    }

    public void delattr(Class<? extends GAttrib> c) {
	setattr(attrclass(c), null);
    }

    public Supplier<? extends Pipe.Op> eqpoint(String nm, Message dat) {
	for(GAttrib attr : this.attr.values()) {
	    if(attr instanceof EquipTarget) {
		Supplier<? extends Pipe.Op> ret = ((EquipTarget)attr).eqpoint(nm, dat);
		if(ret != null)
		    return(ret);
	    }
	}
	return(null);
    }

    public static class GobClick extends Clickable {
	public final Gob gob;

	public GobClick(Gob gob) {
	    this.gob = gob;
	}

	public Object[] clickargs(ClickData cd) {
	    Object[] ret = {0, (int)gob.id, gob.rc.floor(OCache.posres), 0, -1};
	    for(Object node : cd.array()) {
		if(node instanceof Overlay) {
		    ret[0] = 1;
		    ret[3] = ((Overlay)node).id;
		}
		if(node instanceof FastMesh.ResourceMesh)
		    ret[4] = ((FastMesh.ResourceMesh)node).id;
	    }
	    return(ret);
	}

	public String toString() {
	    return(String.format("#<gob-click %d %s>", gob.id, gob.getres()));
	}
    }

    protected void obstate(Pipe buf) {
    }

    private class GobState implements Pipe.Op {
	final Pipe.Op mods;

	private GobState() {
	    if(setupmods.isEmpty()) {
		this.mods = null;
	    } else {
		Pipe.Op[] mods = new Pipe.Op[setupmods.size()];
		int n = 0;
		for(SetupMod mod : setupmods) {
		    if((mods[n] = mod.gobstate()) != null)
			n++;
		}
		this.mods = (n > 0) ? Pipe.Op.compose(mods) : null;
	    }
	}

	public void apply(Pipe buf) {
	    if(!virtual)
		buf.prep(new GobClick(Gob.this));
	    buf.prep(new TickList.Monitor(Gob.this));
	    obstate(buf);
	    if(mods != null)
		buf.prep(mods);
	}

	public boolean equals(GobState that) {
	    return(Utils.eq(this.mods, that.mods));
	}
	public boolean equals(Object o) {
	    return((o instanceof GobState) && equals((GobState)o));
	}
    }
    private GobState curstate = null;
    private GobState curstate() {
	if(curstate == null)
	    curstate = new GobState();
	return(curstate);
    }

    private void updstate() {
	GobState nst;
	try {
	    nst = new GobState();
	} catch(Loading l) {
	    return;
	}
	if(!Utils.eq(nst, curstate)) {
	    for(RenderTree.Slot slot : slots)
		slot.ostate(nst);
	    this.curstate = nst;
	}
    }

    public void added(RenderTree.Slot slot) {
	slot.ostate(curstate());
	for(Overlay ol : ols) {
	    if(ol.slots != null)
		slot.add(ol);
	}
	for(GAttrib a : attr.values()) {
	    if(a instanceof RenderTree.Node)
		slot.add((RenderTree.Node)a);
	}
	slots.add(slot);
    }

    public void removed(RenderTree.Slot slot) {
	slots.remove(slot);
    }

    private Waitable.Queue updwait = null;
    void updated() {
	synchronized(this) {
	    updateseq++;
	    if(updwait != null)
		updwait.wnotify();
	}
    }

    public void updwait(Runnable callback, Consumer<Waitable.Waiting> reg) {
	/* Caller should probably synchronize on this already for a
	 * call like this to even be meaningful, but just in case. */
	synchronized(this) {
	    if(updwait == null)
		updwait = new Waitable.Queue();
	    reg.accept(updwait.add(callback));
	}
    }

    public static class DataLoading extends Loading {
	public final transient Gob gob;
	public final int updseq;

	/* It would be assumed that the caller has synchronized on gob
	 * while creating this exception. */
	public DataLoading(Gob gob, String message) {
	    super(message);
	    this.gob = gob;
	    this.updseq = gob.updateseq;
	}

	public void waitfor(Runnable callback, Consumer<Waiting> reg) {
	    synchronized(gob) {
		if(gob.updateseq != this.updseq) {
		    reg.accept(Waiting.dummy);
		    callback.run();
		} else {
		    gob.updwait(callback, reg);
		}
	    }
	}
    }

    public Random mkrandoom() {
	return(Utils.mkrandoom(id));
    }

    public Resource getres() {
	Drawable d = getattr(Drawable.class);
	if(d != null)
	    return(d.getres());
	return(null);
    }

    public Skeleton.Pose getpose() {
	Drawable d = getattr(Drawable.class);
	if(d != null)
	    return(d.getpose());
	return(null);
    }

    private static final ClassResolver<Gob> ctxr = new ClassResolver<Gob>()
	.add(Gob.class, g -> g)
	.add(Glob.class, g -> g.glob)
	.add(Session.class, g -> g.glob.sess);
    public <T> T context(Class<T> cl) {return(ctxr.context(cl, this));}

    @Deprecated
    public Glob glob() {return(context(Glob.class));}

    /* Because generic functions are too nice a thing for Java. */
    public double getv() {
	Moving m = getattr(Moving.class);
	if(m == null)
	    return(0);
	return(m.getv());
    }

    public Collection<Location.Chain> getloc() {
	Collection<Location.Chain> ret = new ArrayList<>(slots.size());
	for(RenderTree.Slot slot : slots)
	    ret.add(slot.state().get(Homo3D.loc));
	return(ret);
    }

    public class Placed implements RenderTree.Node, TickList.Ticking, TickList.TickNode {
	private final Collection<RenderTree.Slot> slots = new ArrayList<>(1);
	private Placement cur;

	private Placed() {}

	private class Placement implements Pipe.Op {
	    final Pipe.Op flw, tilestate, mods;
	    final Coord3f oc, rc;
	    final double a;

	    Placement() {
		try {
		    Following flw = Gob.this.getattr(Following.class);
		    Pipe.Op flwxf = (flw == null) ? null : flw.xf();
		    Pipe.Op tilestate = null;
		    if(flwxf == null) {
			Coord3f oc = Gob.this.getc();
			Coord3f rc = new Coord3f(oc);
			rc.y = -rc.y;
			this.flw = null;
			this.oc = oc;
			this.rc = rc;
			this.a = Gob.this.a;
			tilestate = Gob.this.getmapstate(oc);
		    } else {
			this.flw = flwxf;
			this.oc = this.rc = null;
			this.a = Double.NaN;
		    }
		    this.tilestate = tilestate;
		    if(setupmods.isEmpty()) {
			this.mods = null;
		    } else {
			Pipe.Op[] mods = new Pipe.Op[setupmods.size()];
			int n = 0;
			for(SetupMod mod : setupmods) {
			    if((mods[n] = mod.placestate()) != null)
				n++;
			}
			this.mods = (n > 0) ? Pipe.Op.compose(mods) : null;
		    }
		} catch(Loading bl) {
		    throw(new Loading(bl) {
			    public String getMessage() {return(bl.getMessage());}

			    public void waitfor(Runnable callback, Consumer<Waiting> reg) {
				Waitable.or(callback, reg, bl, Gob.this::updwait);
			    }
			});
		}
	    }

	    public boolean equals(Placement that) {
		if(this.flw != null) {
		    if(!Utils.eq(this.flw, that.flw))
			return(false);
		} else {
		    if(!(Utils.eq(this.oc, that.oc) && (this.a == that.a)))
			return(false);
		}
		if(!Utils.eq(this.tilestate, that.tilestate))
		    return(false);
		if(!Utils.eq(this.mods, that.mods))
		    return(false);
		return(true);
	    }

	    public boolean equals(Object o) {
		return((o instanceof Placement) && equals((Placement)o));
	    }

	    Pipe.Op gndst = null;
	    public void apply(Pipe buf) {
		if(this.flw != null) {
		    this.flw.apply(buf);
		} else {
		    if(gndst == null)
			gndst = Pipe.Op.compose(new Location(Transform.makexlate(new Matrix4f(), this.rc), "gobx"),
						new Location(Transform.makerot(new Matrix4f(), Coord3f.zu, (float)-this.a), "gob"));
		    gndst.apply(buf);
		}
		if(tilestate != null)
		    tilestate.apply(buf);
		if(mods != null)
		    mods.apply(buf);
	    }
	}

	public Pipe.Op placement() {
	    return(new Placement());
	}

	public void autotick(double dt) {
	    synchronized(Gob.this) {
		Placement np;
		try {
		    np = new Placement();
		} catch(Loading l) {
		    return;
		}
		if(!Utils.eq(this.cur, np))
		    update(np);
	    }
	}

	private void update(Placement np) {
	    for(RenderTree.Slot slot : slots)
		slot.ostate(np);
	    this.cur = np;
	}

	public void added(RenderTree.Slot slot) {
	    slot.ostate(curplace());
	    slot.add(Gob.this);
	    slots.add(slot);
	}

	public void removed(RenderTree.Slot slot) {
	    slots.remove(slot);
	}

	public Pipe.Op curplace() {
	    if(cur == null)
		cur = new Placement();
	    return(cur);
	}

	public Coord3f getc() {
	    return((this.cur != null) ? this.cur.oc : null);
	}

	public TickList.Ticking ticker() {return(this);}
    }
    public final Placed placed = new Placed();

	public String getResName(){
		if(getres()!=null)
			return getres().name;
		return null;
	}


	public void removeol(Class<? extends Sprite> spr ) {
		synchronized (ols){
			for (Overlay ol: ols){
				if(ol.spr.getClass() == spr){
					if(ol.spr instanceof NSprite){
						((NSprite)ol.spr).setIsKilled(true);
					}
					ol.delign = false;
				}
			}
		}
	}

	public void removeolIf(Predicate<? super Overlay> filter) {
		Objects.requireNonNull(filter);
		final Iterator<Overlay> each = ols.iterator();
		while (each.hasNext()) {
			Overlay ol = each.next();
			if (filter.test(ol)) {
				ol.delign = false;
			}
		}
	}


	public Overlay findol(Class<? extends Sprite> spr ) {
			for (Overlay ol : ols) {
				if (ol.spr.getClass() == spr)
					return (ol);
			}
		return(null);
	}

}
