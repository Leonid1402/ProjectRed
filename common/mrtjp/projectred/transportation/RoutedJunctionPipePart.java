package mrtjp.projectred.transportation;

import java.util.BitSet;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.PriorityBlockingQueue;

import mrtjp.projectred.core.Configurator;
import mrtjp.projectred.core.PRColors;
import mrtjp.projectred.core.utils.ItemKey;
import mrtjp.projectred.core.utils.ItemKeyStack;
import mrtjp.projectred.core.utils.Pair2;
import mrtjp.projectred.transportation.RoutedPayload.SendPriority;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.Icon;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeDirection;
import codechicken.lib.data.MCDataInput;
import codechicken.lib.data.MCDataOutput;
import codechicken.lib.vec.BlockCoord;
import codechicken.multipart.IRandomDisplayTick;

public class RoutedJunctionPipePart extends BasicPipePart implements IWorldRouter, IRouteLayer, IWorldRequester, IRandomDisplayTick
{
    public int linkMap;

    public Router router;
    public String routerID;
    public Object routerIDLock = new Object();

    public boolean needsWork = true;
    public boolean firstTick = true;

    public static int pipes = 0;
    public int searchDelay = 0;

    private LinkedList<RoutedPayload> sendQueue = new LinkedList<RoutedPayload>();

    private PriorityBlockingQueue<Pair2<RoutedPayload, Integer>> transitQueue = new PriorityBlockingQueue<Pair2<RoutedPayload, Integer>>(10, new TransitComparator());

    private class TransitComparator implements Comparator<Pair2<RoutedPayload, Integer>>
    {
        @Override
        public int compare(Pair2<RoutedPayload, Integer> o1, Pair2<RoutedPayload, Integer> o2)
        {
            int c = o2.getValue2() - o1.getValue2();
            if (c == 0)
                c = o2.getValue1().payload.compareTo(o1.getValue1().payload);
            return c;
        }
    }

    public RoutedJunctionPipePart()
    {
        pipes++;
        searchDelay = pipes % Configurator.detectionFrequency;
    }

    @Override
    public Router getRouter()
    {
        if (needsWork)
            return null;

        if (router == null)
            synchronized (routerIDLock)
            {
                UUID id = null;
                if (routerID != null && !routerID.isEmpty())
                    id = UUID.fromString(routerID);
                router = RouterServices.instance.getOrCreateRouter(id, this);
            }

        return router;
    }

    @Override
    public void itemEnroute(RoutedPayload r)
    {
        transitQueue.add(new Pair2<RoutedPayload, Integer>(r, 200));
    }

    @Override
    public void itemArrived(RoutedPayload r)
    {
        removeFromTransitQueue(r);

        if (this instanceof IWorldRequester)
            ((IWorldRequester) this).trackedItemReceived(r.payload);
    }

    private void removeFromTransitQueue(RoutedPayload r)
    {
        Iterator<Pair2<RoutedPayload, Integer>> it = transitQueue.iterator();
        while (it.hasNext())
        {
            Pair2<RoutedPayload, Integer> pair = it.next();
            if (pair.getValue1() == r)
            {
                it.remove();
                break;
            }
        }
    }

    private void tickTransitQueue()
    {
        Iterator<Pair2<RoutedPayload, Integer>> it = transitQueue.iterator();
        while (it.hasNext())
        {
            Pair2<RoutedPayload, Integer> pair = it.next();
            Integer val = pair.getValue2();
            if (val == null)
                val = 0;
            val--;
            if (val < 0)
                it.remove();
            else
                pair.setValue2(val);
        }
    }

    protected int countInTransit(ItemKey key)
    {
        int count = 0;
        Iterator<Pair2<RoutedPayload, Integer>> it = transitQueue.iterator();
        while (it.hasNext())
        {
            Pair2<RoutedPayload, Integer> pair = it.next();
            Integer val = pair.getValue2();
            ItemKeyStack stack = pair.getValue1().payload;
            if (stack.key().equals(key))
                count += val;
        }
        return count;
    }

    @Override
    public void queueStackToSend(ItemStack stack, int dirOfExtraction, SyncResponse path)
    {
        queueStackToSend(stack, dirOfExtraction, path.priority, path.responder);
    }

    @Override
    public void queueStackToSend(ItemStack stack, int dirOfExtraction, SendPriority priority, int destination)
    {
        RoutedPayload r = new RoutedPayload(ItemKeyStack.get(stack));
        r.input = ForgeDirection.getOrientation(dirOfExtraction);
        r.setPriority(priority);
        r.setDestination(destination);
        sendQueue.addLast(r);
    }

    private void dispatchQueuedPayload(RoutedPayload r)
    {
        injectPayload(r, r.input);
        Router dest = RouterServices.instance.getRouter(r.destinationIP);
        if (dest != null)
        {
            IWorldRouter wr = dest.getParent();
            wr.itemEnroute(r);
            RouteFX.spawnType1(RouteFX.color_sync, 8, new BlockCoord(wr.getContainer().tile()), world());
        }

        RouteFX.spawnType1(RouteFX.color_send, 8, new BlockCoord(tile()), world());
    }

    @Override
    public SyncResponse getLogisticPath(ItemKey stack, BitSet exclusions, boolean excludeStart)
    {
        LogisticPathFinder p = new LogisticPathFinder(getRouter(), stack);
        if (exclusions != null)
            p.setExclusions(exclusions);
        p.setExcludeSource(excludeStart).findBestResult();
        return p.getResult();
    }

    @Override
    public SyncResponse getSyncResponse(ItemKey item, SyncResponse rival)
    {
        return null;
    }

    @Override
    public final void update()
    {
        if (needsWork)
        {
            needsWork = false;
            coldBoot();
            return;
        }
        if (!world().isRemote)
            getRouter().update(world().getTotalWorldTime() % Configurator.detectionFrequency == searchDelay || firstTick);

        super.update();
        firstTick = false;

        // Dispatch queued items
        while (!sendQueue.isEmpty())
            dispatchQueuedPayload(sendQueue.removeFirst());

        // Manage transit queue
        tickTransitQueue();
        
        if (world().isRemote)
            updateClient();
        else
            updateServer();
    }
    
    protected void updateServer()
    {
    }
    
    protected void updateClient()
    {
        if (world().getTotalWorldTime() % (Configurator.detectionFrequency*20) == searchDelay || firstTick)
            for (int i = 0; i < 6; i++)
                if ((linkMap & 1<<i) != 0)
                    RouteFX.spawnType3(RouteFX.color_blink, 1, i, getCoords(), world());
    }

    private void coldBoot()
    {
        if (!world().isRemote)
            getRouter();
    }

    @Override
    public boolean needsWork()
    {
        return needsWork;
    }

    @Override
    public boolean refreshState()
    {
        if (world().isRemote)
            return false;
        int link = 0;
        for (ForgeDirection d : ForgeDirection.VALID_DIRECTIONS)
            if (getRouter().LSAConnectionExists(d))
                link |= 1 << d.ordinal();
        if (linkMap != link)
        {
            linkMap = link;
            sendLinkMapUpdate();
            return true;
        }
        return false;
    }

    @Override
    public BasicPipePart getContainer()
    {
        return this;
    }

    @Override
    public boolean activate(EntityPlayer player, MovingObjectPosition hit, ItemStack item)
    {
        return false;
    }

    @Override
    public void read(MCDataInput packet, int switch_key)
    {
        if (switch_key == 51)
            handleLinkMap(packet);
        else
            super.read(packet, switch_key);
    }
    
    private void handleLinkMap(MCDataInput packet)
    {
        int old = linkMap;
        linkMap = packet.readUByte();
        
        int high = linkMap & ~old;
        int low = ~linkMap & old;
        
        BlockCoord bc = getCoords();
        for (int i = 0; i < 6; i++)
        {
            if ((high & 1<<i) != 0)
                RouteFX.spawnType3(RouteFX.color_linked, 1, i, bc, world());
            if ((low & 1<<i) != 0)
                RouteFX.spawnType3(RouteFX.color_unlinked, 1, i, bc, world());
        }
        
        tile().markRender();
    }

    public void sendLinkMapUpdate()
    {
        getWriteStream().writeByte(51).writeByte(linkMap);
    }

    @Override
    public Icon getIcon(int side)
    {
        if ((linkMap & 1 << side) != 0)
            return EnumPipe.ROUTEDJUNCTION.sprites[0];
        else
            return EnumPipe.ROUTEDJUNCTION.sprites[1];
    }

    @Override
    public String getType()
    {
        return "pr_rbasic";
    }

    @Override
    public void onRemoved()
    {
        super.onRemoved();
        pipes = Math.max(pipes - 1, 0);
        Router r = getRouter();
        if (r != null)
            r.decommission();
    }
        
    @Override
    public void save(NBTTagCompound tag)
    {
        super.save(tag);
        synchronized (routerIDLock)
        {
            if (routerID == null || routerID.isEmpty())
                if (router != null)
                    routerID = getRouter().getID().toString();
                else
                    routerID = UUID.randomUUID().toString();
        }
        tag.setString("rid", routerID);
    }

    @Override
    public void load(NBTTagCompound tag)
    {
        super.load(tag);
        synchronized (routerIDLock)
        {
            routerID = tag.getString("rid");
        }
    }

    @Override
    public void writeDesc(MCDataOutput packet)
    {
        super.writeDesc(packet);
        packet.writeByte(linkMap);
    }

    @Override
    public void readDesc(MCDataInput packet)
    {
        super.readDesc(packet);
        linkMap = packet.readByte();
    }

    @Override
    public void resolveDestination(RoutedPayload r)
    {
        if (needsWork())
            return;

        int color = -1;
        r.output = ForgeDirection.UNKNOWN;

        // Reroute item if it needs one
        r.refreshIP();
        if (r.destinationIP < 0 || r.destinationIP >= 0 && r.hasArrived)
        {
            r.resetTrip();
            LogisticPathFinder f = new LogisticPathFinder(getRouter(), r.payload.key()).setExclusions(r.travelLog).findBestResult();
            if (f.getResult() != null)
            {
                r.setDestination(f.getResult().responder).setPriority(f.getResult().priority);
                color = RouteFX.color_route;
            }
        }
        r.refreshIP();

        // Deliver item, or reroute
        if (r.destinationIP > 0 && r.destinationUUID.equals(getRouter().getID()))
        {
            r.output = getDirForIncomingItem(r);
            if (r.output == ForgeDirection.UNKNOWN)
            {
                r.resetTrip();
                LogisticPathFinder f = new LogisticPathFinder(getRouter(), r.payload.key()).setExclusions(r.travelLog).findBestResult();
                if (f.getResult() != null)
                {
                    r.setDestination(f.getResult().responder).setPriority(f.getResult().priority);
                    color = RouteFX.color_route;
                }
            }
            r.hasArrived = true;
            itemArrived(r);
            r.travelLog.set(getRouter().getIPAddress());
            color = RouteFX.color_receive;
        }

        // Relay item
        if (r.output == ForgeDirection.UNKNOWN)
        {
            r.output = getRouter().getExitDirection(r.destinationIP);
            color = RouteFX.color_relay;
        }

        // Set to wander, clear travel log
        if (r.output == ForgeDirection.UNKNOWN)
        {
            super.resolveDestination(r);
            r.resetTrip();
            r.travelLog.clear();
            color = RouteFX.color_routeLost;
        }
        RouteFX.spawnType1(color, 8, new BlockCoord(tile()), world());
        adjustSpeed(r);
    }

    public ForgeDirection getDirForIncomingItem(RoutedPayload r)
    {
        return ForgeDirection.UNKNOWN;
    }

    @Override
    public void adjustSpeed(RoutedPayload r)
    {
        r.speed = r.priority.boost;
    }

    @Override
    public void trackedItemLost(ItemKeyStack s)
    {
    }

    @Override
    public void trackedItemReceived(ItemKeyStack s)
    {
    }

    @Override
    public int getActiveFreeSpace(ItemKey item)
    {
        return Integer.MAX_VALUE;
    }

    @Override
    public IWorldRouter getWorldRouter()
    {
        return this;
    }

    @Override
    public IWorldBroadcaster getBroadcaster()
    {
        if (this instanceof IWorldBroadcaster)
            return (IWorldBroadcaster) this;

        return null;
    }

    @Override
    public IWorldRequester getRequester()
    {
        return this;
    }

    @Override
    public World getWorld()
    {
        return world();
    }

    @Override
    public BlockCoord getCoords()
    {
        return new BlockCoord(tile());
    }

    @Override
    public void randomDisplayTick(Random rand)
    {
//        if (rand.nextInt(100) == 0)
//        {
//            for (int i = 0; i < 6; i++)
//                if ((linkMap & 1<<i) != 0)
//                    RouteFX.spawnType3(RouteFX.color_blink, 1, i, getCoords(), world());
//        }
    }
}
