package se.fitchi.app

import androidx.activity.result.ActivityResult
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Läser steg, sömn och puls från Health Connect (dit Samsung Health delar sin data). */
@CapacitorPlugin(name = "FitchiHalsa")
class FitchiHalsaPlugin : Plugin() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val behorigheter = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class)
    )

    private fun tillganglig(): Int = HealthConnectClient.getSdkStatus(context)

    @PluginMethod
    fun status(call: PluginCall) {
        val ret = JSObject()
        val sdk = tillganglig()
        ret.put("sdk", sdk)
        ret.put("tillganglig", sdk == HealthConnectClient.SDK_AVAILABLE)
        if (sdk != HealthConnectClient.SDK_AVAILABLE) {
            ret.put("behorighet", false); ret.put("antal", 0); call.resolve(ret); return
        }
        scope.launch {
            try {
                val givna = HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
                ret.put("behorighet", givna.containsAll(behorigheter))
                ret.put("antal", givna.intersect(behorigheter).size)
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Kunde inte läsa behörigheter: ${e.message}")
            }
        }
    }

    @PluginMethod
    fun begar(call: PluginCall) {
        if (tillganglig() != HealthConnectClient.SDK_AVAILABLE) {
            call.reject("Health Connect finns inte på telefonen"); return
        }
        val kontrakt = PermissionController.createRequestPermissionResultContract()
        val intent = kontrakt.createIntent(context, behorigheter)
        startActivityForResult(call, intent, "begarKlar")
    }

    @ActivityCallback
    fun begarKlar(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val givna = PermissionController.createRequestPermissionResultContract()
            .parseResult(result.resultCode, result.data)
        val ret = JSObject()
        ret.put("behorighet", givna.containsAll(behorigheter))
        ret.put("antal", givna.intersect(behorigheter).size)
        call.resolve(ret)
    }

    @PluginMethod
    fun las(call: PluginCall) {
        if (tillganglig() != HealthConnectClient.SDK_AVAILABLE) {
            call.reject("Health Connect finns inte på telefonen"); return
        }
        scope.launch {
            val ret = JSObject()
            try {
                val klient = HealthConnectClient.getOrCreate(context)
                val givna = klient.permissionController.getGrantedPermissions()
                val zon = ZoneId.systemDefault()
                val nu = Instant.now()
                val idag = LocalDate.now(zon).atStartOfDay(zon).toInstant()
                val igar = idag.minus(1, ChronoUnit.DAYS)

                // Steg idag och igår
                if (givna.contains(HealthPermission.getReadPermission(StepsRecord::class))) {
                    // Vilka appar skriver steg idag? Telefon, klocka och andra appar kan räkna samma steg.
                    val perApp = mutableMapOf<String, Long>()
                    var sida: String? = null
                    do {
                        val svar = klient.readRecords(ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(idag, nu), pageToken = sida))
                        for (r in svar.records) perApp[r.metadata.dataOrigin.packageName] = (perApp[r.metadata.dataOrigin.packageName] ?: 0L) + r.count
                        sida = svar.pageToken
                    } while (sida != null)
                    val kallor = JSObject(); perApp.forEach { (k, v) -> kallor.put(k, v) }
                    ret.put("stegKallor", kallor)
                    // Samsung Health räknar redan ihop telefon och klocka, så använd bara dess siffra när den finns
                    val samsung = "com.sec.android.app.shealth"
                    val bara = if (perApp.containsKey(samsung)) setOf(DataOrigin(samsung)) else emptySet()
                    ret.put("stegKalla", if (bara.isEmpty()) "Health Connect" else "Samsung Health")
                    val a = klient.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(idag, nu), bara))
                    ret.put("steg", a[StepsRecord.COUNT_TOTAL] ?: 0L)
                    val b = klient.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(igar, idag), bara))
                    ret.put("stegIgar", b[StepsRecord.COUNT_TOTAL] ?: 0L)
                }

                // Senaste nattens sömn: sessioner som slutar efter kl 18 igår
                if (givna.contains(HealthPermission.getReadPermission(SleepSessionRecord::class))) {
                    val fran = idag.minus(6, ChronoUnit.HOURS)
                    val sessioner = klient.readRecords(
                        ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(fran.minus(12, ChronoUnit.HOURS), nu))
                    ).records.filter { it.endTime.isAfter(fran) }
                    var minuter = 0L
                    var vaken = 0L
                    var slut: Instant? = null
                    for (s in sessioner) {
                        minuter += Duration.between(s.startTime, s.endTime).toMinutes()
                        for (st in s.stages) {
                            if (st.stage == SleepSessionRecord.STAGE_TYPE_AWAKE || st.stage == SleepSessionRecord.STAGE_TYPE_OUT_OF_BED) {
                                vaken += Duration.between(st.startTime, st.endTime).toMinutes()
                            }
                        }
                        if (slut == null || s.endTime.isAfter(slut)) slut = s.endTime
                    }
                    ret.put("somnMin", (minuter - vaken).coerceAtLeast(0))
                    ret.put("somnSessioner", sessioner.size)
                    if (slut != null) ret.put("somnSlut", slut.toString())
                }

                // Vilopuls (senaste 3 dygnen) och puls idag
                if (givna.contains(HealthPermission.getReadPermission(RestingHeartRateRecord::class))) {
                    val vila = klient.readRecords(
                        ReadRecordsRequest(RestingHeartRateRecord::class, TimeRangeFilter.between(nu.minus(3, ChronoUnit.DAYS), nu))
                    ).records.maxByOrNull { it.time }
                    if (vila != null) ret.put("vilopuls", vila.beatsPerMinute)
                }
                if (givna.contains(HealthPermission.getReadPermission(HeartRateRecord::class))) {
                    val p = klient.aggregate(AggregateRequest(setOf(HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MIN), TimeRangeFilter.between(igar, nu)))
                    p[HeartRateRecord.BPM_AVG]?.let { ret.put("pulsSnitt", it) }
                    p[HeartRateRecord.BPM_MIN]?.let { ret.put("pulsMin", it) }
                    // Senaste mätningen (puls just nu), senaste 3 timmarna
                    val nyliga = klient.readRecords(
                        ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(nu.minus(3, ChronoUnit.HOURS), nu))
                    ).records.flatMap { it.samples }.maxByOrNull { it.time }
                    if (nyliga != null) {
                        ret.put("pulsNu", nyliga.beatsPerMinute)
                        ret.put("pulsNuMinSedan", Duration.between(nyliga.time, nu).toMinutes())
                    }
                }
                ret.put("last", nu.toString())
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Kunde inte läsa hälsodata: ${e.message}")
            }
        }
    }
}
