import Foundation
import Capacitor
import HealthKit

/// Läser steg, sömn, puls och träningspass från Apple Hälsa (HealthKit).
/// Samma metoder och fält som Android-versionen (FitchiHalsaPlugin.kt), så webbappen kan vara densamma.
@objc(FitchiHalsaPlugin)
public class FitchiHalsaPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "FitchiHalsaPlugin"
    public let jsName = "FitchiHalsa"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "status", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "begar", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "las", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "widget", returnType: CAPPluginReturnPromise)
    ]

    private let store = HKHealthStore()
    private let iso = ISO8601DateFormatter()

    private var lasTyper: Set<HKObjectType> {
        var s = Set<HKObjectType>()
        if let t = HKObjectType.quantityType(forIdentifier: .stepCount) { s.insert(t) }
        if let t = HKObjectType.quantityType(forIdentifier: .heartRate) { s.insert(t) }
        if let t = HKObjectType.quantityType(forIdentifier: .restingHeartRate) { s.insert(t) }
        if let t = HKObjectType.categoryType(forIdentifier: .sleepAnalysis) { s.insert(t) }
        s.insert(HKObjectType.workoutType())
        return s
    }

    /// Apple Hälsas träningstyp -> Fitchis passtyp (samma nycklar som i appen).
    private func kategori(_ t: HKWorkoutActivityType) -> String {
        switch t {
        case .traditionalStrengthTraining, .functionalStrengthTraining, .highIntensityIntervalTraining,
             .crossTraining, .coreTraining:
            return "styrka"
        case .running: return "lopning"
        case .walking, .hiking: return "promenad"
        case .cycling: return "cykel"
        case .swimming: return "simning"
        case .yoga, .pilates, .flexibility, .mindAndBody, .cooldown: return "yoga"
        case .socialDance, .cardioDance: return "dans"
        case .tennis, .squash, .badminton, .racquetball, .tableTennis: return "padel"
        default: return "annat"
        }
    }

    // MARK: - status

    @objc func status(_ call: CAPPluginCall) {
        guard HKHealthStore.isHealthDataAvailable() else {
            call.resolve(["tillganglig": false, "behorighet": false, "antal": 0, "plattform": "ios"])
            return
        }
        // iOS avslöjar inte om användaren sa ja eller nej till läsning (integritet).
        // Vi kan bara veta om frågan redan har ställts.
        store.getRequestStatusForAuthorization(toShare: [], read: lasTyper) { status, _ in
            let fragat = (status == .unnecessary)
            call.resolve([
                "tillganglig": true,
                "behorighet": fragat,
                "antal": fragat ? self.lasTyper.count : 0,
                "trening": fragat,
                "plattform": "ios"
            ])
        }
    }

    // MARK: - begar

    @objc func begar(_ call: CAPPluginCall) {
        guard HKHealthStore.isHealthDataAvailable() else {
            call.reject("Apple Hälsa finns inte på den här enheten"); return
        }
        store.requestAuthorization(toShare: nil, read: lasTyper) { ok, fel in
            if let fel = fel { call.reject("Kunde inte fråga om behörighet: \(fel.localizedDescription)"); return }
            call.resolve(["behorighet": ok, "antal": ok ? self.lasTyper.count : 0])
        }
    }

    // MARK: - widget (ingen hemskärmswidget på iPhone ännu)

    @objc func widget(_ call: CAPPluginCall) {
        call.resolve()
    }

    // MARK: - las

    @objc func las(_ call: CAPPluginCall) {
        guard HKHealthStore.isHealthDataAvailable() else {
            call.reject("Apple Hälsa finns inte på den här enheten"); return
        }
        let kal = Calendar.current
        let nu = Date()
        let idag = kal.startOfDay(for: nu)
        let igar = kal.date(byAdding: .day, value: -1, to: idag)!

        var ret: JSObject = [:]
        let las = NSLock()
        func satt(_ k: String, _ v: JSValue) { las.lock(); ret[k] = v; las.unlock() }
        let grupp = DispatchGroup()

        // Steg idag och igår. Apple Hälsa lägger själv ihop iPhone och klocka utan dubbelräkning.
        if let stegTyp = HKQuantityType.quantityType(forIdentifier: .stepCount) {
            grupp.enter()
            summa(stegTyp, fran: idag, till: nu) { v in
                satt("steg", Int(v ?? 0)); satt("stegKallaPaket", "com.apple.health"); grupp.leave()
            }
            grupp.enter()
            summa(stegTyp, fran: igar, till: idag) { v in
                satt("stegIgar", Int(v ?? 0)); grupp.leave()
            }
            // Steg per dag senaste veckan
            grupp.enter()
            let veckaStart = kal.date(byAdding: .day, value: -6, to: idag)!
            let q = HKStatisticsCollectionQuery(
                quantityType: stegTyp,
                quantitySamplePredicate: HKQuery.predicateForSamples(withStart: veckaStart, end: nu),
                options: .cumulativeSum, anchorDate: idag, intervalComponents: DateComponents(day: 1))
            q.initialResultsHandler = { _, resultat, _ in
                var dagar: JSObject = [:]
                resultat?.enumerateStatistics(from: veckaStart, to: nu) { st, _ in
                    if let v = st.sumQuantity()?.doubleValue(for: .count()) {
                        dagar[String(self.dagNr(st.startDate))] = Int(v)
                    }
                }
                satt("stegDagar", dagar); grupp.leave()
            }
            store.execute(q)
        }

        // Träningspass sedan i går
        grupp.enter()
        prover(HKObjectType.workoutType(), fran: igar, till: nu) { prov in
            var pass: JSArray = []
            for case let w as HKWorkout in prov {
                let min = Int(w.endDate.timeIntervalSince(w.startDate) / 60)
                if min < 5 { continue }
                let o: JSObject = [
                    "id": w.uuid.uuidString,
                    "typ": self.kategori(w.workoutActivityType),
                    "htyp": Int(w.workoutActivityType.rawValue),
                    "titel": "",
                    "min": min,
                    "start": Int(w.startDate.timeIntervalSince1970 * 1000),
                    "slut": Int(w.endDate.timeIntervalSince1970 * 1000),
                    "kalla": w.sourceRevision.source.bundleIdentifier
                ]
                pass.append(o)
            }
            satt("pass", pass); grupp.leave()
        }

        // Senaste nattens sömn: perioder som slutar efter kl 18 igår
        if let somnTyp = HKObjectType.categoryType(forIdentifier: .sleepAnalysis) {
            grupp.enter()
            let gransen = idag.addingTimeInterval(-6 * 3600)
            prover(somnTyp, fran: gransen.addingTimeInterval(-12 * 3600), till: nu) { prov in
                let alla = prov.compactMap { $0 as? HKCategorySample }.filter { $0.endDate > gransen }
                var sover = alla.filter { self.arSomn($0.value) }
                // Bara iPhone (ingen klocka): då finns bara "i sängen" från sömnschemat
                if sover.isEmpty { sover = alla.filter { $0.value == HKCategoryValueSleepAnalysis.inBed.rawValue } }
                // Klocka och andra appar kan registrera samma natt, så överlapp räknas bara en gång
                let perioder = self.slaIhop(sover.map { ($0.startDate, $0.endDate) })
                let minuter = perioder.reduce(0.0) { $0 + $1.1.timeIntervalSince($1.0) } / 60
                satt("somnMin", Int(minuter))
                satt("somnSessioner", self.slaIhop(perioder, glapp: 3600).count)
                if let slut = perioder.map({ $0.1 }).max() { satt("somnSlut", self.iso.string(from: slut)) }
                grupp.leave()
            }
        }

        // Vilopuls (senaste 3 dygnen)
        if let vilaTyp = HKQuantityType.quantityType(forIdentifier: .restingHeartRate) {
            grupp.enter()
            senaste(vilaTyp, fran: nu.addingTimeInterval(-3 * 86400)) { s in
                if let s = s { satt("vilopuls", Int(s.quantity.doubleValue(for: self.slagPerMin).rounded())) }
                grupp.leave()
            }
        }

        // Puls sedan i går och puls just nu
        if let pulsTyp = HKQuantityType.quantityType(forIdentifier: .heartRate) {
            grupp.enter()
            let q = HKStatisticsQuery(
                quantityType: pulsTyp,
                quantitySamplePredicate: HKQuery.predicateForSamples(withStart: igar, end: nu),
                options: [.discreteAverage, .discreteMin]) { _, st, _ in
                if let v = st?.averageQuantity()?.doubleValue(for: self.slagPerMin) { satt("pulsSnitt", Int(v.rounded())) }
                if let v = st?.minimumQuantity()?.doubleValue(for: self.slagPerMin) { satt("pulsMin", Int(v.rounded())) }
                grupp.leave()
            }
            store.execute(q)

            grupp.enter()
            senaste(pulsTyp, fran: nu.addingTimeInterval(-3 * 3600)) { s in
                if let s = s {
                    satt("pulsNu", Int(s.quantity.doubleValue(for: self.slagPerMin).rounded()))
                    satt("pulsNuMinSedan", Int(nu.timeIntervalSince(s.endDate) / 60))
                }
                grupp.leave()
            }
        }

        grupp.notify(queue: .main) {
            ret["last"] = self.iso.string(from: nu)
            call.resolve(ret)
        }
    }

    // MARK: - hjälpare

    private let slagPerMin = HKUnit.count().unitDivided(by: .minute())

    /// Samma dagnummer som webbappens dagNr(): dagar sedan 1970-01-01 i lokal tid.
    private func dagNr(_ d: Date) -> Int {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        let lokal = Calendar.current.dateComponents([.year, .month, .day], from: d)
        let dag = utc.date(from: lokal)!
        return Int(floor(dag.timeIntervalSince1970 / 86400))
    }

    private func arSomn(_ v: Int) -> Bool {
        if v == HKCategoryValueSleepAnalysis.asleepUnspecified.rawValue { return true }
        if #available(iOS 16.0, *) {
            return v == HKCategoryValueSleepAnalysis.asleepCore.rawValue
                || v == HKCategoryValueSleepAnalysis.asleepDeep.rawValue
                || v == HKCategoryValueSleepAnalysis.asleepREM.rawValue
        }
        return false
    }

    /// Slår ihop tidsperioder som överlappar (eller ligger närmare än `glapp` sekunder).
    private func slaIhop(_ perioder: [(Date, Date)], glapp: TimeInterval = 0) -> [(Date, Date)] {
        let sorterade = perioder.sorted { $0.0 < $1.0 }
        var ut: [(Date, Date)] = []
        for p in sorterade {
            if let sista = ut.last, p.0.timeIntervalSince(sista.1) <= glapp {
                ut[ut.count - 1].1 = max(sista.1, p.1)
            } else {
                ut.append(p)
            }
        }
        return ut
    }

    private func summa(_ typ: HKQuantityType, fran: Date, till: Date, klar: @escaping (Double?) -> Void) {
        let q = HKStatisticsQuery(
            quantityType: typ,
            quantitySamplePredicate: HKQuery.predicateForSamples(withStart: fran, end: till),
            options: .cumulativeSum) { _, st, _ in
            klar(st?.sumQuantity()?.doubleValue(for: .count()))
        }
        store.execute(q)
    }

    private func prover(_ typ: HKSampleType, fran: Date, till: Date, klar: @escaping ([HKSample]) -> Void) {
        let q = HKSampleQuery(
            sampleType: typ,
            predicate: HKQuery.predicateForSamples(withStart: fran, end: till),
            limit: HKObjectQueryNoLimit,
            sortDescriptors: [NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)]) { _, prov, _ in
            klar(prov ?? [])
        }
        store.execute(q)
    }

    private func senaste(_ typ: HKQuantityType, fran: Date, klar: @escaping (HKQuantitySample?) -> Void) {
        let q = HKSampleQuery(
            sampleType: typ,
            predicate: HKQuery.predicateForSamples(withStart: fran, end: Date()),
            limit: 1,
            sortDescriptors: [NSSortDescriptor(key: HKSampleSortIdentifierEndDate, ascending: false)]) { _, prov, _ in
            klar(prov?.first as? HKQuantitySample)
        }
        store.execute(q)
    }
}
