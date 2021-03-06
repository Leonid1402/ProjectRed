package mrtjp.projectred.transportation;

import java.util.LinkedList;
import java.util.List;

import org.lwjgl.input.Keyboard;

import mrtjp.projectred.core.inventory.InventoryWrapper;
import mrtjp.projectred.core.utils.ItemKey;
import mrtjp.projectred.transportation.ItemRoutingChip.EnumRoutingChip;
import mrtjp.projectred.transportation.RoutedPayload.SendPriority;
import mrtjp.projectred.transportation.RoutingChipset.UpgradeBus;
import net.minecraft.inventory.IInventory;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumChatFormatting;

public class RoutingChipset_ItemOverflowResponder extends RoutingChipset
{
    private final SendPriority priority = getSendPriority();
    public int preference = 0;

    protected SendPriority getSendPriority()
    {
        return SendPriority.DEFAULT;
    }

    public void prefUp()
    {
        if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT))
            preference += 10;
        else
            preference += 1;
        if (preference > prefScale())
            preference = prefScale();
    }

    public void prefDown()
    {
        if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT))
            preference -= 10;
        else
            preference -= 1;
        if (preference < -prefScale())
            preference = -prefScale();
    }
    
    private int prefScale()
    {
        return 2 + getUpgradeBus().LLatency();
    }

    @Override
    public SyncResponse getSyncResponse(ItemKey item, SyncResponse rival)
    {
        IInventory real = inventoryProvider().getInventory();
        int side = inventoryProvider().getInterfacedSide();

        if (real == null || side < 0)
            return null;

        if (priority.ordinal() > rival.priority.ordinal() || priority.ordinal() == rival.priority.ordinal() && preference > rival.customPriority)
        {
            InventoryWrapper inv = InventoryWrapper.wrapInventory(real).setSlotsFromSide(side);
            int room = inv.getRoomAvailableForItem(item);
            if (room > 0)
                return new SyncResponse().setPriority(priority).setCustomPriority(preference).setItemCount(room);
        }
        return null;
    }

    @Override
    public void save(NBTTagCompound tag)
    {
        super.save(tag);
        tag.setInteger("pref", preference);
    }

    @Override
    public void load(NBTTagCompound tag)
    {
        super.load(tag);
        preference = tag.getInteger("pref");
    }

    @Override
    public List<String> infoCollection()
    {
        List<String> list = new LinkedList<String>();
        addPriorityInfo(list);
        return list;
    }

    @Override
    public EnumRoutingChip getChipType()
    {
        return EnumRoutingChip.ITEMOVERFLOWRESPONDER;
    }

    public void addPriorityInfo(List<String> list)
    {
        list.add(EnumChatFormatting.GRAY + "Preference: " + preference);
    }
    
    @Override
    public UpgradeBus createUpgradeBus()
    {
        UpgradeBus b = new UpgradeBus(3, 0);
        b.setLatency(3, 5, 54, 0, 0, 0);
        b.Linfo = "raise maximum preference value";
        b.Lformula = "preference value = 2 + Latency";
        return b;
    }
}
