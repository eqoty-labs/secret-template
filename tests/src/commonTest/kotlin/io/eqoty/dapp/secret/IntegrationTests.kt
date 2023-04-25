package io.eqoty.dapp.secret

import DeployContractUtils
import co.touchlab.kermit.Logger
import io.eqoty.cosmwasm.std.types.ContractInfo
import io.eqoty.dapp.secret.TestGlobals.client
import io.eqoty.dapp.secret.TestGlobals.contractInfo
import io.eqoty.dapp.secret.TestGlobals.initTestsSemaphore
import io.eqoty.dapp.secret.TestGlobals.initializeClient
import io.eqoty.dapp.secret.TestGlobals.needsInit
import io.eqoty.dapp.secret.TestGlobals.testnetInfo
import io.eqoty.dapp.secret.types.contract.CountResponse
import io.eqoty.dapp.secret.utils.*
import io.eqoty.secretk.types.MsgExecuteContract
import io.eqoty.secretk.types.MsgInstantiateContract
import io.eqoty.secretk.types.TxOptions
import io.getenv
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okio.Path
import okio.Path.Companion.toPath
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class IntegrationTests {

    private val contractCodePath: Path = getenv(Constants.CONTRACT_PATH_ENV_NAME)!!.toPath()

    // Initialization procedure
    private suspend fun initializeAndUploadContract() {
        val endpoint = testnetInfo.grpcGatewayEndpoint

        client = initializeClient(endpoint, testnetInfo.chainId)

        BalanceUtils.fillUpFromFaucet(testnetInfo, client, 100_000_000)

        val initMsg = """{"count": 4}"""
        val instantiateMsgs = listOf(
            MsgInstantiateContract(
                sender = client.senderAddress,
                codeId = null, // will be set later
                initMsg = initMsg,
                label = "My Snip721" + ceil(Random.nextDouble() * 10000),
                codeHash = null // will be set later
            )
        )
        contractInfo = DeployContractUtils.getOrStoreCodeAndInstantiate(
            client,
            contractCodePath,
            instantiateMsgs
        ).run {
            ContractInfo(
                address,
                codeInfo.codeHash
            )
        }
    }

    private suspend fun queryCount(): CountResponse {
        val contractInfoQuery = """{"get_count": {}}"""
        return Json.decodeFromString(
            client.queryContractSmart(
                contractInfo.address,
                contractInfoQuery
            )
        )
    }

    private suspend fun incrementTx(
        contractInfo: ContractInfo
    ) {
        val incrementMsg = """{"increment": {}}"""

        val msgs1 = listOf(
            MsgExecuteContract(
                sender = client.senderAddress,
                contractAddress = contractInfo.address,
                codeHash = contractInfo.codeHash,
                msg = incrementMsg,
            )
        )
        val gasLimit = 200000
        val result = client.execute(
            msgs1,
            txOptions = TxOptions(gasLimit = gasLimit)
        )
        Logger.i("Increment TX used ${result.gasUsed}")
    }


    @BeforeTest
    fun beforeEach() = runTest {
        initTestsSemaphore.acquire()
        try {
            if (needsInit) {
                Logger.setTag("dapp")
                initializeAndUploadContract()
                needsInit = false
            }
        } catch (t: Throwable) {
            throw t
        } finally {
            initTestsSemaphore.release()
        }
    }

    @Test
    fun test_count_on_initialization() = runTest {
        val countResponse = queryCount()
        Logger.i("Count Response: $countResponse")
        assertEquals(4, countResponse.count)
    }

    @Test
    fun test_increment_stress() = runTest {
        val onStartCounter = queryCount().count

        val stressLoad = 10
        for (i in 0 until 10) {
            incrementTx(contractInfo)
        }

        val afterStressCounter = queryCount().count
        assertEquals(
            stressLoad, afterStressCounter - onStartCounter,
            "After running stress test the counter expected to be ${onStartCounter + 10} instead " +
                    "of ${afterStressCounter}"
        )
    }

}
