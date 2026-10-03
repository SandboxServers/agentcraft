package dev.agentcraft.client.mp;

import dev.agentcraft.layout.Anchors;
import dev.agentcraft.layout.Anchors.Layout;
import dev.agentcraft.mp.*;
import dev.agentcraft.mp.state.*;
import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/** Client-thread registry; listeners run on the same thread as the mutation. */
public final class Studios {
    private static final Map<StudioId,StudioView> VIEWS=new LinkedHashMap<>();
    private static final Map<StudioId,Plot> PLOTS=new LinkedHashMap<>();
    private static final Map<StudioId,Integer> SLOTS=new LinkedHashMap<>();
    private static final List<BiConsumer<StudioId,StudioView>> LISTENERS=new ArrayList<>();
    private static final List<BiConsumer<StudioId,PublicEvent>> EVENT_LISTENERS=new ArrayList<>();
    private static StudioId ownId=StudioId.LOCAL;
    private static @Nullable StudioId overlay;
    private static int nextSlot=1;
    private Studios() {}
    public static void init() {
        Anchors.addStudioListener((id,layout)->Minecraft.getInstance().execute(()->updateLayout(id,layout)));
    }
    public static StudioView own() {
        StudioView old=VIEWS.get(ownId);
        return new StudioView(ownId,true,old==null?"":old.ownerName(),old==null || old.online(),Anchors.forStudio(ownId),old==null?null:old.publicState(),0);
    }
    public static Optional<StudioView> view(StudioId id) { return id.equals(ownId)?Optional.of(own()):Optional.ofNullable(VIEWS.get(id)); }
    public static Collection<StudioView> all() {
        List<StudioView> result=new ArrayList<>(); result.add(own());
        VIEWS.values().stream().filter(v->!v.id().equals(ownId)).forEach(result::add); return List.copyOf(result);
    }
    public static Optional<StudioView> at(BlockPos pos) {
        if(overlay!=null && in(own(),pos)) return view(overlay);
        // Plot boundaries win; layout bounds are a fallback while the plot is not yet known.
        for(StudioView v:all()) if(PLOTS.containsKey(v.id()) && PLOTS.get(v.id()).contains(pos)) return Optional.of(v);
        Optional<Plot> plot=Plots.directory().plotAt(pos);
        if(plot.isPresent()) { Optional<StudioView> v=view(plot.get().owner()); if(v.isPresent()) return v; }
        for(StudioView v:all()) if(!PLOTS.containsKey(v.id()) && bounds(v.layout(),pos)) return Optional.of(v);
        return Optional.empty();
    }
    private static boolean in(StudioView v,BlockPos p) {
        Plot plot=PLOTS.get(v.id());
        if(plot==null) plot=Plots.directory().plotOf(v.id()).orElse(null);
        return plot==null ? bounds(v.layout(),p) : plot.contains(p);
    }
    private static boolean bounds(Layout l,BlockPos p) { return l.bounds()!=null && l.bounds().contains(p.getX(),p.getY(),p.getZ()); }
    public static int entityIdBase(StudioId id) {
        int slot=id.equals(ownId)?0:SLOTS.computeIfAbsent(id,k->nextSlot++);
        return Math.subtractExact(-10000,Math.multiplyExact(1000,slot));
    }
    public static void addListener(BiConsumer<StudioId,StudioView> listener) { LISTENERS.add(listener); }
    public static void addEventListener(BiConsumer<StudioId,PublicEvent> listener) { EVENT_LISTENERS.add(listener); }
    public static void fireEvent(StudioId id,PublicEvent event) { EVENT_LISTENERS.forEach(l->l.accept(id,event)); }
    public static void setOwn(StudioId id) {
        ownId=id; SLOTS.put(id,0); Anchors.setSelf(id);
    }
    public static void setPlot(StudioId id,int index,int stride) {
        if(index<0) PLOTS.remove(id); else PLOTS.put(id,new Plot(index,id,PlotGrid.originOf(index,stride)));
    }
    public static void put(StudioView view) {
        boolean own=view.id().equals(ownId); int slot=own?0:slot(view.id());
        StudioView next=new StudioView(view.id(),own,MpText.sanitize(view.ownerName(),16),view.online(),view.layout(),view.publicState(),slot);
        VIEWS.put(view.id(),next); LISTENERS.forEach(l->l.accept(next.id(),next));
    }
    private static int slot(StudioId id) { if(id.equals(ownId)) return 0; entityIdBase(id); return SLOTS.get(id); }
    public static void updateLayout(StudioId id,Layout layout) {
        StudioView old=view(id).orElse(new StudioView(id,false,"",false,Layout.EMPTY,null,slot(id)));
        put(new StudioView(id,old.own(),old.ownerName(),old.online(),layout,old.publicState(),old.slot()));
    }
    public static void updateState(StudioId id,PublicStudioState state) {
        StudioView old=view(id).orElse(new StudioView(id,false,"",false,Layout.EMPTY,null,slot(id)));
        put(new StudioView(id,old.own(),old.ownerName(),old.online(),old.layout(),state,old.slot()));
    }
    public static void updatePresence(StudioId id,String name,boolean online) {
        StudioView old=view(id).orElse(new StudioView(id,false,"",false,Layout.EMPTY,null,slot(id)));
        put(new StudioView(id,old.own(),name,online,old.layout(),old.publicState(),old.slot()));
    }
    public static void setOverlay(@Nullable StudioId id) { overlay=id; }
    public static void remove(StudioId id) {
        VIEWS.remove(id); PLOTS.remove(id); if(id.equals(overlay)) overlay=null;
        LISTENERS.forEach(l->l.accept(id,null));
    }
    public static void reset() {
        for(StudioId id:List.copyOf(VIEWS.keySet())) remove(id);
        PLOTS.clear(); SLOTS.clear(); nextSlot=1; overlay=null; ownId=StudioId.LOCAL; Anchors.setSelf(StudioId.LOCAL);
    }
}
