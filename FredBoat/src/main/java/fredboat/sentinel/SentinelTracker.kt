package fredboat.sentinel

import com.fredboat.sentinel.SentinelExchanges
import com.fredboat.sentinel.entities.FredBoatHello
import com.fredboat.sentinel.entities.SentinelHello
import fredboat.agent.FredBoatAgent
import fredboat.agent.HelloSender
import fredboat.config.property.AppConfig
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/** Class that tracks Sentinels and their routing keys */
@Service
class SentinelTracker(private val appConfig: AppConfig, rabbit: RabbitTemplate, helloSender: HelloSender) {
    companion object {
        private val log: Logger = LoggerFactory.getLogger(SentinelTracker::class.java)
    }

    init {
        fun hello(startup: Boolean) {
            rabbit.convertAndSend(SentinelExchanges.FANOUT, "", FredBoatHello(startup, appConfig.status))
        }

        val task = launch {
            log.info("Sending FredBoat hello")
            hello(true)
            delay(5000)
            while (map.isEmpty()) {
                log.info("Still haven't received hello from any sentinel. Resending hello....")
                hello(false)
                delay(5000)
            }
        }
        task.invokeOnCompletion {
            FredBoatAgent.start(helloSender)
        }
    }

    /** Shard id mapped to [SentinelHello] */
    private val map: ConcurrentHashMap<Int, SentinelHello> = ConcurrentHashMap()
    val sentinels: Set<SentinelHello>
        get() = map.values.toSet()

    fun onHello(hello: SentinelHello) = hello.run {
        log.info("Received hello from $key with shards [$shardStart;$shardEnd] \uD83D\uDC4B")

        if (shardCount != appConfig.shardCount) {
            throw IllegalStateException("Received SentinelHello from $key with shard count $shardCount shards, " +
                    "but we are configured for ${appConfig.shardCount}!")
        }

        (shardStart..shardEnd).forEach {
            map[it] = hello
        }
    }

    fun getHello(shardId: Int) = map[shardId]
    fun getKey(shardId: Int): String {
        val hello = getHello(shardId)
                ?: throw IllegalStateException("Attempted to access routing key of $shardId," +
                        " but we haven't received hello from it.")
        return hello.key
    }
}