package net.corda.gold.trading

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.concurrent.CordaFuture
import net.corda.core.contracts.StateAndRef
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.flows.StartableByService
import net.corda.core.internal.FlowAsyncOperation
import net.corda.core.schemas.MappedSchema
import net.corda.core.serialization.CordaSerializable
import org.hibernate.annotations.Type
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import javax.persistence.*

@Entity
@Table(name = "account_broadcast", indexes = [Index(name = "account_pk_idx", columnList = "account_uuid")])
@CordaSerializable
data class AccountBroadcastInfo(

        @Id
        @Column(name = "account_uuid", unique = true, nullable = false)
        @Type(type = "uuid-char")
        var accountUUID: UUID?,

        @Column(name = "broadcast_accounts", nullable = false)
        @ElementCollection(fetch = FetchType.EAGER)
        @Type(type = "uuid-char")
        var broadcastAccounts: List<UUID>?


) : MappedSchema(AccountBroadcastInfo::class.java, 1, listOf(AccountBroadcastInfo::class.java)) {
    constructor() : this(null, null)
}

class BroadcastToCarbonCopyReceiversFlow(
        private val owningAccount: AccountInfo,
        private val stateToBroadcast: StateAndRef<*>,
        private val carbonCopyReceivers: Collection<AccountInfo>? = null
) : FlowLogic<Unit>() {

    @Suspendable
    override fun call() {
        val accountService = serviceHub.cordaService(KeyManagementBackedAccountService::class.java)
        owningAccount.accountId.let { accountThatOwnedStateId ->
            val accountsToBroadCastTo = carbonCopyReceivers
                    ?: subFlow(GetAllInterestedAccountsFlow(accountThatOwnedStateId))
            for (accountToBroadcastTo in accountsToBroadCastTo) {
                accountService.broadcastStateToAccount(accountToBroadcastTo.accountId, stateToBroadcast)
            }
        }
    }
}


@StartableByRPC
@StartableByService
class AddBroadcastAccountToAccountFlow(val accountUUID: UUID, val accountToPermission: UUID, val add: Boolean = true) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        serviceHub.withEntityManager {
            val existingEntry = find(AccountBroadcastInfo::class.java, accountUUID)
                    ?: AccountBroadcastInfo(accountUUID = accountUUID, broadcastAccounts = listOf())
            val modifiedEntry = if (add) {
                existingEntry.broadcastAccounts = existingEntry.broadcastAccounts!! + listOf(accountToPermission)
                existingEntry
            } else {
                existingEntry.broadcastAccounts = existingEntry.broadcastAccounts!! - listOf(accountToPermission)
                existingEntry
            }
            persist(modifiedEntry)
        }
    }
}

@StartableByRPC
class GetAllInterestedAccountsFlow(val accountId: UUID) : FlowLogic<List<AccountInfo>>() {
    @Suspendable
    override fun call(): List<AccountInfo> {
        val refHolder = AtomicReference<AccountBroadcastInfo?>()
        serviceHub.withEntityManager(Consumer { em ->
            val foundAccount = em.find(AccountBroadcastInfo::class.java, accountId)
            val loadedAccount = foundAccount?.copy(broadcastAccounts = foundAccount.broadcastAccounts?.map { it }
                    ?: listOf())
            loadedAccount?.broadcastAccounts?.size
            refHolder.set(loadedAccount)
        })
        val accountService = serviceHub.cordaService(KeyManagementBackedAccountService::class.java)
        return refHolder.get()?.let { it.broadcastAccounts?.mapNotNull(getAccountFromAccountId(accountService)) }
                ?: listOf()
    }

    private fun getAccountFromAccountId(accountService: KeyManagementBackedAccountService) =
            { accountId: UUID -> accountService.accountInfo(accountId)?.state?.data }
}

class BroadcastOperation(
        private val accountService: KeyManagementBackedAccountService,
        private val accountToBroadcastTo: AccountInfo,
        private val stateToBroadcast: StateAndRef<*>
) : FlowAsyncOperation<Unit> {

    @Suspendable
    override fun execute(deduplicationId: String): CordaFuture<Unit> {
        return accountService.broadcastStateToAccount(accountToBroadcastTo.accountId, stateToBroadcast)
    }
}