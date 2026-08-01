package org.helix.bridge.paper

import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

/**
 * Registers the Helix coin economy as the server's Vault economy provider,
 * so Vault-consuming plugins (shops, jobs, …) read and change the same
 * network-wide balances the `eco.*` actions manage.
 *
 * Only referenced when the Vault plugin is present — this class touching
 * Vault types must never be loaded otherwise.
 */
object VaultEconomyHook {
    /**
     * Registers the provider with Bukkit's services manager.
     *
     * @param plugin the bridge plugin owning the registration.
     * @param actions node action channel for balance changes.
     * @param bridgeValues supplier of the synced bridge values, the fast
     *   read path for online players' balances.
     */
    fun register(plugin: JavaPlugin, actions: BridgeActionInvoker, bridgeValues: () -> Map<String, String>) {
        plugin.server.servicesManager.register(
            Economy::class.java,
            HelixVaultEconomy(actions, bridgeValues),
            plugin,
            ServicePriority.Normal,
        )
    }
}

/**
 * Vault [Economy] backed by the Helix economy addon.
 *
 * Reads resolve from the synced `economy.balance.<name>` bridge values when
 * available (online players, no round-trip) and otherwise ask the node's
 * `eco.api.balance`. Deposits and withdrawals go through the
 * `eco.api.deposit`/`eco.api.withdraw` actions; amounts are whole coins, so
 * fractional digits are truncated. Banks are not supported.
 *
 * @property actions node action channel.
 * @property bridgeValues supplier of the synced bridge values.
 */
class HelixVaultEconomy(
    private val actions: BridgeActionInvoker,
    private val bridgeValues: () -> Map<String, String>,
) : Economy {
    /** Whether the provider is usable. */
    override fun isEnabled(): Boolean = true

    /** The provider name shown by Vault. */
    override fun getName(): String = "Helix"

    /** Helix has no bank accounts. */
    override fun hasBankSupport(): Boolean = false

    /** Coins are whole numbers. */
    override fun fractionalDigits(): Int = 0

    /**
     * Formats an amount for display.
     *
     * @param amount the amount.
     * @return the formatted text, for example `12 coins`.
     */
    override fun format(amount: Double): String {
        val whole = amount.toLong()
        return if (whole == 1L) "$whole ${currencyNameSingular()}" else "$whole ${currencyNamePlural()}"
    }

    /** Plural currency name. */
    override fun currencyNamePlural(): String = "coins"

    /** Singular currency name. */
    override fun currencyNameSingular(): String = "coin"

    /** Every known player implicitly has an account (starting balance). */
    override fun hasAccount(playerName: String): Boolean = true

    /** Every known player implicitly has an account (starting balance). */
    override fun hasAccount(player: OfflinePlayer): Boolean = true

    /** World-agnostic: same as [hasAccount]. */
    override fun hasAccount(playerName: String, worldName: String): Boolean = true

    /** World-agnostic: same as [hasAccount]. */
    override fun hasAccount(player: OfflinePlayer, worldName: String): Boolean = true

    /**
     * A player's balance, from the synced bridge value or the node.
     *
     * @param playerName the player name.
     * @return the balance in whole coins.
     */
    override fun getBalance(playerName: String): Double {
        val synced = bridgeValues()["economy.balance.${playerName.lowercase()}"]?.toDoubleOrNull()
        if (synced != null) {
            return synced
        }
        return actions.invoke("eco.api.balance", listOf(playerName))
            ?.takeIf { it.success }
            ?.lines
            ?.firstOrNull()
            ?.toDoubleOrNull()
            ?: 0.0
    }

    /** Balance by [OfflinePlayer]. */
    override fun getBalance(player: OfflinePlayer): Double = getBalance(player.name ?: return 0.0)

    /** World-agnostic: same as [getBalance]. */
    override fun getBalance(playerName: String, world: String): Double = getBalance(playerName)

    /** World-agnostic: same as [getBalance]. */
    override fun getBalance(player: OfflinePlayer, world: String): Double = getBalance(player)

    /** Whether the balance covers an amount. */
    override fun has(playerName: String, amount: Double): Boolean = getBalance(playerName) >= amount

    /** Whether the balance covers an amount. */
    override fun has(player: OfflinePlayer, amount: Double): Boolean = getBalance(player) >= amount

    /** World-agnostic: same as [has]. */
    override fun has(playerName: String, worldName: String, amount: Double): Boolean = has(playerName, amount)

    /** World-agnostic: same as [has]. */
    override fun has(player: OfflinePlayer, worldName: String, amount: Double): Boolean = has(player, amount)

    /**
     * Withdraws whole coins through the node.
     *
     * @param playerName the player name.
     * @param amount the amount; fractions are truncated.
     * @return the Vault response with the new balance.
     */
    override fun withdrawPlayer(playerName: String, amount: Double): EconomyResponse =
        change("eco.api.withdraw", playerName, amount)

    /** Withdraw by [OfflinePlayer]. */
    override fun withdrawPlayer(player: OfflinePlayer, amount: Double): EconomyResponse =
        withdrawPlayer(player.name ?: "", amount)

    /** World-agnostic: same as [withdrawPlayer]. */
    override fun withdrawPlayer(playerName: String, worldName: String, amount: Double): EconomyResponse =
        withdrawPlayer(playerName, amount)

    /** World-agnostic: same as [withdrawPlayer]. */
    override fun withdrawPlayer(player: OfflinePlayer, worldName: String, amount: Double): EconomyResponse =
        withdrawPlayer(player, amount)

    /**
     * Deposits whole coins through the node.
     *
     * @param playerName the player name.
     * @param amount the amount; fractions are truncated.
     * @return the Vault response with the new balance.
     */
    override fun depositPlayer(playerName: String, amount: Double): EconomyResponse =
        change("eco.api.deposit", playerName, amount)

    /** Deposit by [OfflinePlayer]. */
    override fun depositPlayer(player: OfflinePlayer, amount: Double): EconomyResponse =
        depositPlayer(player.name ?: "", amount)

    /** World-agnostic: same as [depositPlayer]. */
    override fun depositPlayer(playerName: String, worldName: String, amount: Double): EconomyResponse =
        depositPlayer(playerName, amount)

    /** World-agnostic: same as [depositPlayer]. */
    override fun depositPlayer(player: OfflinePlayer, worldName: String, amount: Double): EconomyResponse =
        depositPlayer(player, amount)

    /** Accounts exist implicitly; creating one is always successful. */
    override fun createPlayerAccount(playerName: String): Boolean = true

    /** Accounts exist implicitly; creating one is always successful. */
    override fun createPlayerAccount(player: OfflinePlayer): Boolean = true

    /** World-agnostic: same as [createPlayerAccount]. */
    override fun createPlayerAccount(playerName: String, worldName: String): Boolean = true

    /** World-agnostic: same as [createPlayerAccount]. */
    override fun createPlayerAccount(player: OfflinePlayer, worldName: String): Boolean = true

    /** Banks are not supported. */
    override fun createBank(name: String, player: String): EconomyResponse = noBanks()

    /** Banks are not supported. */
    override fun createBank(name: String, player: OfflinePlayer): EconomyResponse = noBanks()

    /** Banks are not supported. */
    override fun deleteBank(name: String): EconomyResponse = noBanks()

    /** Banks are not supported. */
    override fun bankBalance(name: String): EconomyResponse = noBanks()

    /** Banks are not supported. */
    override fun bankHas(name: String, amount: Double): EconomyResponse = noBanks()

    /** Banks are not supported. */
    override fun bankWithdraw(name: String, amount: Double): EconomyResponse = noBanks()

    /** Banks are not supported. */
    override fun bankDeposit(name: String, amount: Double): EconomyResponse = noBanks()

    /** Banks are not supported. */
    override fun isBankOwner(name: String, playerName: String): EconomyResponse = noBanks()

    /** Banks are not supported. */
    override fun isBankOwner(name: String, player: OfflinePlayer): EconomyResponse = noBanks()

    /** Banks are not supported. */
    override fun isBankMember(name: String, playerName: String): EconomyResponse = noBanks()

    /** Banks are not supported. */
    override fun isBankMember(name: String, player: OfflinePlayer): EconomyResponse = noBanks()

    /** Banks are not supported. */
    override fun getBanks(): List<String> = emptyList()

    private fun change(action: String, playerName: String, amount: Double): EconomyResponse {
        if (playerName.isBlank() || amount < 0) {
            return EconomyResponse(0.0, getBalance(playerName), EconomyResponse.ResponseType.FAILURE, "invalid request")
        }
        val whole = amount.toLong()
        val result = actions.invoke(action, listOf(playerName, whole.toString()))
            ?: return EconomyResponse(
                0.0,
                getBalance(playerName),
                EconomyResponse.ResponseType.FAILURE,
                "node unreachable",
            )
        if (!result.success) {
            return EconomyResponse(
                0.0,
                getBalance(playerName),
                EconomyResponse.ResponseType.FAILURE,
                result.lines.firstOrNull() ?: "rejected",
            )
        }
        val newBalance = result.lines.firstOrNull()?.toDoubleOrNull() ?: getBalance(playerName)
        return EconomyResponse(whole.toDouble(), newBalance, EconomyResponse.ResponseType.SUCCESS, null)
    }

    private fun noBanks(): EconomyResponse =
        EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Helix has no bank support")
}
