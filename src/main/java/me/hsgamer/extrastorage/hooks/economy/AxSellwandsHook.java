package me.hsgamer.extrastorage.hooks.economy;

import com.artillexstudios.axsellwands.api.events.AxSellwandsSellEvent;
import com.artillexstudios.axsellwands.hooks.HookManager;
import com.artillexstudios.axsellwands.hooks.currency.CurrencyHook;
import com.artillexstudios.axsellwands.hooks.shop.AdvancedPricesHook;
import com.artillexstudios.axsellwands.hooks.shop.PricesHook;
import com.artillexstudios.axsellwands.hooks.shop.SellResult;
import me.hsgamer.extrastorage.data.log.Log;
import me.hsgamer.extrastorage.util.Digital;
import me.hsgamer.extrastorage.util.ItemUtil;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.function.Consumer;

/**
 * AxSellwands 经济提供商钩子
 * 使用 AxSellwands API 来出售物品
 */
public final class AxSellwandsHook implements EconomyProvider {

    private boolean setup = false;

    public AxSellwandsHook() {
        if (this.isHooked()) {
            instance.getLogger().info("Using AxSellwands as economy provider.");
            instance.getMetrics().addCustomChart(new SimplePie("economy_provider", () -> "AxSellwands"));
        } else {
            instance.getLogger().severe("Could not find dependency: AxSellwands. Please install it then try again!");
        }
    }

    @Override
    public boolean isHooked() {
        if (Bukkit.getPluginManager().getPlugin("AxSellwands") == null) {
            return false;
        }
        if (!setup) {
            try {
                // 检查 HookManager 是否可用
                PricesHook pricesHook = HookManager.getShopPrices();
                CurrencyHook currencyHook = HookManager.getCurrency();
                
                if (pricesHook == null) {
                    instance.getLogger().warning("AxSellwands price hook is not available!");
                    return false;
                }
                
                if (currencyHook == null) {
                    instance.getLogger().warning("AxSellwands currency hook is not available!");
                    return false;
                }
                
                setup = true;
                return true;
            } catch (Exception e) {
                instance.getLogger().warning("Failed to hook into AxSellwands: " + e.getMessage());
                return false;
            }
        }
        return true;
    }

    @Override
    public int getAmount(ItemStack item) {
        if (!this.isHooked()) {
            return 0;
        }

        try {
            PricesHook pricesHook = HookManager.getShopPrices();
            if (pricesHook == null) {
                return 0;
            }

            // 检查物品是否有价格
            double price = pricesHook.getPrice(item);
            if (price > 0) {
                // 返回 1 作为默认数量（表示可以出售）
                return 1;
            }
        } catch (Exception e) {
            instance.getLogger().warning("Error getting amount from AxSellwands: " + e.getMessage());
        }
        
        return 0;
    }

    @Override
    public String getPrice(Player player, ItemStack item, int amount) {
        if (!this.isHooked()) {
            return null;
        }

        try {
            PricesHook pricesHook = HookManager.getShopPrices();
            if (pricesHook == null) {
                return null;
            }

            double unitPrice;
            
            // 检查是否是高级价格钩子
            if (pricesHook instanceof AdvancedPricesHook) {
                AdvancedPricesHook advancedHook = (AdvancedPricesHook) pricesHook;
                SellResult result = advancedHook.canSell(player, item);
                
                if (!result.canSell() || result.getPrice() <= 0) {
                    return null;
                }
                
                unitPrice = result.getPrice();
            } else {
                // 使用基础价格钩子
                unitPrice = pricesHook.getPrice(player, item);
                
                if (unitPrice <= 0) {
                    return null;
                }
            }

            // 计算总价格
            double totalPrice = unitPrice * amount;
            return Digital.formatDouble("###,###.##", totalPrice);
        } catch (Exception e) {
            instance.getLogger().warning("Error getting price from AxSellwands: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void sellItem(Player player, ItemStack item, int amount, Consumer<Result> result) {
        if (!this.isHooked()) {
            result.accept(new Result(-1, -1, false));
            return;
        }

        try {
            PricesHook pricesHook = HookManager.getShopPrices();
            CurrencyHook currencyHook = HookManager.getCurrency();
            
            if (pricesHook == null || currencyHook == null) {
                result.accept(new Result(-1, -1, false));
                return;
            }

            double unitPrice;
            boolean isAdvanced = pricesHook instanceof AdvancedPricesHook;
            
            // 检查是否可以出售
            if (isAdvanced) {
                AdvancedPricesHook advancedHook = (AdvancedPricesHook) pricesHook;
                SellResult sellResult = advancedHook.canSell(player, item);
                
                if (!sellResult.canSell()) {
                    result.accept(new Result(-1, -1, false));
                    return;
                }
                
                unitPrice = sellResult.getPrice();
            } else {
                unitPrice = pricesHook.getPrice(player, item);
                
                if (unitPrice <= 0) {
                    result.accept(new Result(-1, -1, false));
                    return;
                }
            }

            double totalPrice = unitPrice * amount;

            // 触发 AxSellwands 出售事件
            AxSellwandsSellEvent event = new AxSellwandsSellEvent(player, totalPrice, amount);
            Bukkit.getPluginManager().callEvent(event);

            if (event.isCancelled()) {
                result.accept(new Result(-1, -1, false));
                return;
            }

            // 使用事件中可能被修改的价格
            double finalPrice = event.getMoneyMade();

            // 确认出售（扣除限额等）
            if (isAdvanced) {
                AdvancedPricesHook advancedHook = (AdvancedPricesHook) pricesHook;
                boolean confirmed = advancedHook.confirmSell(player, item, unitPrice);
                
                if (!confirmed) {
                    result.accept(new Result(-1, -1, false));
                    return;
                }
            }

            // 给予玩家金钱
            currencyHook.giveBalance(player, finalPrice);

            // 记录销售日志
            if (instance.getSetting().isLogSales()) {
                instance.getLog().log(player, null, Log.Action.SELL, ItemUtil.toMaterialKey(item), amount, finalPrice);
            }

            // 返回成功结果
            result.accept(new Result(amount, finalPrice, true));
        } catch (Exception e) {
            instance.getLogger().warning("Error selling item with AxSellwands: " + e.getMessage());
            e.printStackTrace();
            result.accept(new Result(-1, -1, false));
        }
    }
}
