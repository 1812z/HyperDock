package y7;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.miui.dock.allapps.Model;
import com.miui.dock.allapps.h0;
import com.miui.gamebooster.utils.e0;
import com.miui.gamebooster.windowmanager.newbox.DockAppAnimLauncher;
import com.miui.gamebooster.windowmanager.newbox.y;
import com.miui.securitycenter.Application;
import com.miui.securitycenter.R;
import gp.c0;
import java.util.Iterator;
import java.util.Map;
import miuix.animation.Folme;
import miuix.animation.IVisibleStyle;
import miuix.animation.base.AnimConfig;
import org.jetbrains.annotations.NotNull;
import ro.t;
import to.f0;

/* JADX INFO: loaded from: classes2.dex */
public abstract class m extends y7.b implements View.OnClickListener, View.OnLongClickListener, View.OnTouchListener {

    /* JADX INFO: renamed from: a, reason: collision with root package name */
    private final p f49639a;

    /* JADX INFO: renamed from: b, reason: collision with root package name */
    private final LinearLayout f49640b;

    /* JADX INFO: renamed from: c, reason: collision with root package name */
    private final ImageView f49641c;

    /* JADX INFO: renamed from: d, reason: collision with root package name */
    private final ImageView f49642d;

    /* JADX INFO: renamed from: e, reason: collision with root package name */
    private final ImageView f49643e;

    /* JADX INFO: renamed from: f, reason: collision with root package name */
    private final ImageView f49644f;

    /* JADX INFO: renamed from: g, reason: collision with root package name */
    private final TextView f49645g;

    /* JADX INFO: renamed from: h, reason: collision with root package name */
    private final Map f49646h;

    /* JADX INFO: renamed from: i, reason: collision with root package name */
    private Model.c f49647i;

    /* JADX INFO: renamed from: j, reason: collision with root package name */
    private final int f49648j;

    /* JADX INFO: renamed from: k, reason: collision with root package name */
    private float f49649k;

    /* JADX INFO: renamed from: l, reason: collision with root package name */
    private float f49650l;

    /* JADX INFO: renamed from: m, reason: collision with root package name */
    private boolean f49651m;

    /* JADX INFO: renamed from: n, reason: collision with root package name */
    private boolean f49652n;

    /* JADX INFO: renamed from: o, reason: collision with root package name */
    private View f49653o;

    /* JADX INFO: renamed from: p, reason: collision with root package name */
    private com.miui.dock.sidebar.p f49654p;

    /* JADX INFO: renamed from: q, reason: collision with root package name */
    private c8.i f49655q;

    /* JADX INFO: renamed from: r, reason: collision with root package name */
    private int f49656r;

    /* JADX INFO: renamed from: s, reason: collision with root package name */
    private View f49657s;

    public /* synthetic */ class a {

        /* JADX INFO: renamed from: a, reason: collision with root package name */
        public static final /* synthetic */ int[] f49658a;

        static {
            int[] iArr = new int[h0.values().length];
            try {
                iArr[h0.PINNABLE.ordinal()] = 1;
            } catch (NoSuchFieldError unused) {
            }
            try {
                iArr[h0.PINNED.ordinal()] = 2;
            } catch (NoSuchFieldError unused2) {
            }
            f49658a = iArr;
        }
    }

    public static final class b implements DockAppAnimLauncher.b {

        /* JADX INFO: renamed from: a, reason: collision with root package name */
        final /* synthetic */ String f49659a;

        /* JADX INFO: renamed from: b, reason: collision with root package name */
        final /* synthetic */ com.miui.dock.sidebar.p f49660b;

        b(String str, com.miui.dock.sidebar.p pVar) {
            this.f49659a = str;
            this.f49660b = pVar;
        }

        @Override // com.miui.gamebooster.windowmanager.newbox.DockAppAnimLauncher.b
        public void a() {
            Log.e("IconViewHolder", this.f49659a + " onAnimLaunchFail");
        }

        @Override // com.miui.gamebooster.windowmanager.newbox.DockAppAnimLauncher.b
        public void b(String str) {
            gp.n.f(str, "pkg");
            Log.d("IconViewHolder", this.f49659a + " onAnimLaunchSuc: " + str);
            this.f49660b.o().r0(this.f49660b);
        }
    }

    /* JADX WARN: 'super' call moved to the top of the method (can break code semantics) */
    public m(View view, p pVar) {
        super(view);
        gp.n.f(view, "itemView");
        this.f49639a = pVar;
        View viewFindViewById = view.findViewById(R.id.rootView);
        gp.n.e(viewFindViewById, "findViewById(...)");
        this.f49640b = (LinearLayout) viewFindViewById;
        View viewFindViewById2 = view.findViewById(R.id.ivIcon);
        gp.n.e(viewFindViewById2, "findViewById(...)");
        this.f49641c = (ImageView) viewFindViewById2;
        View viewFindViewById3 = view.findViewById(R.id.ivPin);
        gp.n.e(viewFindViewById3, "findViewById(...)");
        this.f49642d = (ImageView) viewFindViewById3;
        View viewFindViewById4 = view.findViewById(R.id.ivUnpin);
        gp.n.e(viewFindViewById4, "findViewById(...)");
        this.f49643e = (ImageView) viewFindViewById4;
        View viewFindViewById5 = view.findViewById(R.id.ivPinNotAllowed);
        gp.n.e(viewFindViewById5, "findViewById(...)");
        this.f49644f = (ImageView) viewFindViewById5;
        View viewFindViewById6 = view.findViewById(R.id.tvName);
        gp.n.e(viewFindViewById6, "findViewById(...)");
        this.f49645g = (TextView) viewFindViewById6;
        this.f49646h = f0.m(t.a(h0.PINNABLE, null), t.a(h0.PINNED, null), t.a(h0.UNPINNABLE, null));
        this.f49648j = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        this.f49656r = -1;
    }

    private final void C() {
        final com.miui.dock.sidebar.p pVar;
        c8.i iVar;
        ViewParent parent;
        final View view = this.f49653o;
        if (view == null || (pVar = this.f49654p) == null || (iVar = this.f49655q) == null) {
            return;
        }
        int i10 = this.f49656r;
        this.f49651m = false;
        this.f49652n = true;
        this.f49653o = null;
        this.f49654p = null;
        this.f49655q = null;
        this.f49656r = -1;
        a8.k kVar = new a8.k(pVar, view, iVar, pVar.C().G(), 1, i10, -1, null);
        kVar.q0(new Runnable() { // from class: y7.d
            @Override // java.lang.Runnable
            public final void run() {
                m.D(view, pVar);
            }
        });
        kVar.B();
        kVar.s0();
        View view2 = this.f49657s;
        if (view2 != null && (parent = view2.getParent()) != null) {
            parent.requestDisallowInterceptTouchEvent(false);
        }
        this.f49657s = null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final void D(View view, final com.miui.dock.sidebar.p pVar) {
        view.post(new Runnable() { // from class: y7.j
            @Override // java.lang.Runnable
            public final void run() {
                m.E(pVar);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final void E(com.miui.dock.sidebar.p pVar) {
        pVar.C().z(false);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final boolean m(Model.c cVar) {
        return cVar.getEditState() == h0.PINNABLE;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final boolean n(Model.c cVar) {
        return cVar.getEditState() == h0.PINNED;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final boolean o(Model.c cVar) {
        return cVar.getEditState() == h0.UNPINNABLE;
    }

    private final void p() {
        ViewParent parent;
        View view = this.f49657s;
        if (view != null && (parent = view.getParent()) != null) {
            parent.requestDisallowInterceptTouchEvent(false);
        }
        this.f49657s = null;
        this.f49651m = false;
        this.f49652n = false;
        this.f49653o = null;
        this.f49654p = null;
        this.f49655q = null;
        this.f49656r = -1;
    }

    private final c8.m.a q(final com.miui.dock.sidebar.p pVar, final int i10) {
        final fp.l lVar = new fp.l() { // from class: y7.e
            @Override // fp.l
            public final Object invoke(Object obj) {
                return m.u(pVar, (String) obj);
            }
        };
        return new c8.m.a() { // from class: y7.f
            @Override // c8.m.a
            public final void a(c8.m mVar) {
                m.r(this.f49626a, lVar, i10, mVar);
            }
        };
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final void r(m mVar, fp.l lVar, final int i10, c8.m mVar2) {
        final c0 c0Var = new c0();
        final c8.o oVar = mVar2 instanceof c8.o ? (c8.o) mVar2 : null;
        if (oVar != null) {
            String string = Application.A().getString(R.string.track_side_bar_state_split_screen);
            gp.n.e(string, "getString(...)");
            c0Var.f31339a = string;
            vi.c0.e().b(new Runnable() { // from class: y7.k
                @Override // java.lang.Runnable
                public final void run() {
                    m.s(c0Var, i10, oVar);
                }
            });
            DockAppAnimLauncher.f18770g.a().L(mVar.f49641c, oVar, (DockAppAnimLauncher.b) lVar.invoke("SplitScreen"));
            return;
        }
        final c8.f fVar = mVar2 instanceof c8.f ? (c8.f) mVar2 : null;
        if (fVar != null) {
            String string2 = Application.A().getString(R.string.track_side_bar_state_fullscreen);
            gp.n.e(string2, "getString(...)");
            c0Var.f31339a = string2;
            vi.c0.e().b(new Runnable() { // from class: y7.l
                @Override // java.lang.Runnable
                public final void run() {
                    m.t(c0Var, i10, fVar);
                }
            });
            DockAppAnimLauncher.f18770g.a().H(mVar.f49641c, fVar, (DockAppAnimLauncher.b) lVar.invoke("FullScreen"));
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final void s(c0 c0Var, int i10, c8.o oVar) {
        h8.b.w(false, (String) c0Var.f31339a, i10, oVar.i(), -1, null);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final void t(c0 c0Var, int i10, c8.f fVar) {
        h8.b.w(false, (String) c0Var.f31339a, i10, fVar.i(), -1, null);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static final b u(com.miui.dock.sidebar.p pVar, String str) {
        gp.n.f(str, "mode");
        return new b(str, pVar);
    }

    private final Point w(View view) {
        FrameLayout frameLayoutA = y.f19279o.a(view);
        if (frameLayoutA == null) {
            return null;
        }
        int[] iArr = new int[2];
        view.getLocationInWindow(iArr);
        int[] iArr2 = new int[2];
        frameLayoutA.getLocationInWindow(iArr2);
        return new Point(iArr[0] - iArr2[0], iArr[1] - iArr2[1]);
    }

    protected final boolean A() {
        Model.c cVar = this.f49647i;
        if (cVar == null) {
            gp.n.s("model");
            cVar = null;
        }
        return cVar.getEditState() != h0.NONE;
    }

    public abstract void B(View view);

    public final void F(Model.c cVar) {
        AnimConfig animConfigF;
        gp.n.f(cVar, "model");
        Iterator it = this.f49646h.entrySet().iterator();
        while (true) {
            if (!it.hasNext()) {
                break;
            }
            Map.Entry entry = (Map.Entry) it.next();
            h0 h0Var = (h0) entry.getKey();
            IVisibleStyle iVisibleStyle = (IVisibleStyle) entry.getValue();
            Model.c cVar2 = this.f49647i;
            if (cVar2 == null) {
                gp.n.s("model");
                cVar2 = null;
            }
            h0 editState = cVar2.getEditState();
            h0 h0Var2 = h0.NONE;
            if (editState == h0Var2 || cVar.getEditState() == h0Var2) {
                if (iVisibleStyle != null) {
                    com.miui.dock.allapps.d.c(iVisibleStyle, 0.0f, 1, null);
                }
                animConfigF = com.miui.dock.allapps.d.f(0L, 0L, null, 7, null);
            } else {
                if (iVisibleStyle != null) {
                    com.miui.dock.allapps.d.g(iVisibleStyle);
                }
                animConfigF = com.miui.dock.allapps.d.j(0L, 0L, null, 7, null);
            }
            if (cVar.getEditState() == h0Var) {
                if (iVisibleStyle != null) {
                    iVisibleStyle.show(animConfigF);
                }
            } else if (iVisibleStyle != null) {
                iVisibleStyle.hide(animConfigF);
            }
        }
        this.f49647i = cVar;
        this.f49640b.setHapticFeedbackEnabled(cVar.getEditState() == h0.NONE);
    }

    @Override // y7.b
    public void b() {
        p();
        Folme.clean(this.f49640b, this.f49642d, this.f49643e, this.f49644f);
    }

    public void l(final Model.c cVar) {
        gp.n.f(cVar, "model");
        this.f49647i = cVar;
        this.f49640b.setOnClickListener(this);
        this.f49640b.setOnLongClickListener(this);
        this.f49640b.setOnTouchListener(this);
        this.f49646h.put(h0.PINNABLE, com.miui.dock.allapps.d.n(this.f49642d, new fp.a() { // from class: y7.g
            @Override // fp.a
            public final Object invoke() {
                return Boolean.valueOf(m.m(cVar));
            }
        }));
        this.f49646h.put(h0.PINNED, com.miui.dock.allapps.d.n(this.f49643e, new fp.a() { // from class: y7.h
            @Override // fp.a
            public final Object invoke() {
                return Boolean.valueOf(m.n(cVar));
            }
        }));
        this.f49646h.put(h0.UNPINNABLE, com.miui.dock.allapps.d.n(this.f49644f, new fp.a() { // from class: y7.i
            @Override // fp.a
            public final Object invoke() {
                return Boolean.valueOf(m.o(cVar));
            }
        }));
    }

    @Override // android.view.View.OnClickListener
    public void onClick(@NotNull View view) {
        Model.c cVarJ;
        p pVar;
        gp.n.f(view, "view");
        if (!A()) {
            p pVar2 = this.f49639a;
            if (pVar2 != null) {
                pVar2.a();
            }
            B(view);
            return;
        }
        Model.c cVar = this.f49647i;
        Model.c cVar2 = null;
        if (cVar == null) {
            gp.n.s("model");
            cVar = null;
        }
        int i10 = a.f49658a[cVar.getEditState().ordinal()];
        if (i10 != 1) {
            if (i10 == 2 && (pVar = this.f49639a) != null) {
                Model.c cVar3 = this.f49647i;
                if (cVar3 == null) {
                    gp.n.s("model");
                } else {
                    cVar2 = cVar3;
                }
                pVar.b(cVar2);
                return;
            }
            return;
        }
        Point pointW = w(this.f49641c);
        if (pointW == null) {
            p pVar3 = this.f49639a;
            if (pVar3 != null) {
                Model.c cVar4 = this.f49647i;
                if (cVar4 == null) {
                    gp.n.s("model");
                } else {
                    cVar2 = cVar4;
                }
                pVar3.c(cVar2);
                return;
            }
            return;
        }
        Model.c cVar5 = this.f49647i;
        if (cVar5 == null) {
            gp.n.s("model");
            cVar5 = null;
        }
        if (cVar5 instanceof Model.App) {
            Model.c cVar6 = this.f49647i;
            if (cVar6 == null) {
                gp.n.s("model");
            } else {
                cVar2 = cVar6;
            }
            cVarJ = Model.App.j((Model.App) cVar2, null, false, false, null, false, 31, null);
        } else {
            if (!(cVar5 instanceof Model.Shortcut)) {
                throw new ro.m();
            }
            Model.c cVar7 = this.f49647i;
            if (cVar7 == null) {
                gp.n.s("model");
            } else {
                cVar2 = cVar7;
            }
            cVarJ = Model.Shortcut.j((Model.Shortcut) cVar2, null, false, null, 7, null);
        }
        Bitmap bitmapCreateBitmap = Bitmap.createBitmap(this.f49641c.getWidth(), this.f49641c.getHeight(), Bitmap.Config.ARGB_8888);
        this.f49641c.draw(new Canvas(bitmapCreateBitmap));
        cVarJ.h(new y.a(bitmapCreateBitmap, pointW.x, pointW.y, (int) (this.f49641c.getWidth() * this.f49641c.getScaleX()), (int) (this.f49641c.getHeight() * this.f49641c.getScaleY())));
        p pVar4 = this.f49639a;
        if (pVar4 != null) {
            pVar4.c(cVarJ);
        }
    }

    @Override // android.view.View.OnLongClickListener
    public boolean onLongClick(View view) {
        com.miui.dock.sidebar.p pVarD;
        gp.n.f(view, "view");
        if (A() || !e0.K()) {
            p();
            return true;
        }
        p pVar = this.f49639a;
        if (pVar == null || (pVarD = pVar.d()) == null) {
            p();
            return true;
        }
        Model.c cVar = this.f49647i;
        if (cVar == null) {
            gp.n.s("model");
            cVar = null;
        }
        Context context = view.getContext();
        gp.n.e(context, "getContext(...)");
        c8.i iVarA = cVar.a(context);
        if (iVarA == null) {
            p();
            return true;
        }
        this.f49653o = view;
        this.f49654p = pVarD;
        this.f49655q = iVarA;
        this.f49656r = y();
        this.f49657s = view;
        ViewParent parent = view.getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
        }
        this.f49651m = true;
        this.f49652n = false;
        pVarD.o().J2(pVarD, iVarA, view, q(pVarD, this.f49656r), true);
        return true;
    }

    /* JADX WARN: Code duplicated, block: B:21:0x004b  */
    @Override // android.view.View.OnTouchListener
    public boolean onTouch(View view, MotionEvent motionEvent) {
        gp.n.f(view, "view");
        gp.n.f(motionEvent, "event");
        int actionMasked = motionEvent.getActionMasked();
        if (actionMasked == 0) {
            Folme.use((View) this.f49640b).touch().touchDown(new AnimConfig[0]);
            this.f49649k = motionEvent.getRawX();
            this.f49650l = motionEvent.getRawY();
            p();
        } else if (actionMasked == 1) {
            Folme.use((View) this.f49640b).touch().touchUp(new AnimConfig[0]);
            p();
        } else if (actionMasked != 2) {
            if (actionMasked == 3) {
                Folme.use((View) this.f49640b).touch().touchUp(new AnimConfig[0]);
                p();
            }
        } else if (this.f49651m && !this.f49652n) {
            if (Math.abs(motionEvent.getRawX() - this.f49649k) > this.f49648j || Math.abs(motionEvent.getRawY() - this.f49650l) > this.f49648j) {
                C();
            }
            return true;
        }
        return false;
    }

    protected final ImageView v() {
        return this.f49641c;
    }

    protected final LinearLayout x() {
        return this.f49640b;
    }

    protected final int y() {
        int bindingAdapterPosition = getBindingAdapterPosition();
        if (bindingAdapterPosition != -1) {
            RecyclerView.h bindingAdapter = getBindingAdapter();
            com.miui.dock.allapps.b bVar = bindingAdapter instanceof com.miui.dock.allapps.b ? (com.miui.dock.allapps.b) bindingAdapter : null;
            if (bVar != null) {
                return bVar.l(bindingAdapterPosition);
            }
        }
        return bindingAdapterPosition;
    }

    protected final TextView z() {
        return this.f49645g;
    }
}