package ai.nearby.reunion

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

/** Calls the actual public NaviHelper AAR and the AAOS car service. No fake SDK classes. */
class PlatformAdapter(private val context: Context, private val emit: EventSink) : NativePlatform {
    private val main = Handler(Looper.getMainLooper())
    private val reader = VehicleIpcTasks.runner
    @Volatile private var active = false
    @Volatile private var generation = 0
    private var car: Car? = null
    private var navi: NaviHelper? = null
    private var ux: CarUxRestrictionsManager? = null
    private var properties: CarPropertyManager? = null
    private var connected = false
    private var naviConnected = false
    private var reading = false
    private var vehicleRead = 0L
    private var gearIsPark: Boolean? = null
    private var speed: Float? = null
    private var restrictions: CarUxRestrictions? = null
    private var readAt = 0L
    private var vehicleRevision = 0L
    private var arrivalSeq = 0L
    private val arrivalProof = ArrivalProofRecorder(SystemClock::elapsedRealtime) { Log.i("MajoongArrivalProof", it) }
    private val vehicleSamples = VehicleSampleRecorder(SystemClock::elapsedRealtime) { Log.i("MajoongAudit", it) }
    @Volatile private var carConnection = 0L
    private var vehicleSetupFailed = false
    private var navigationSetupFailed = false
    private val navigationAttempts = ConnectionAttemptDeadline()
    private var navigationTimeout: Runnable? = null
    private var closed = false

    private var naviListener: NaviHelperEventListener? = null
    private var vehicleListener: CarPropertyManager.CarPropertyEventCallback? = null
    private var uxRegistrationOwner: Any? = null

    private fun createNaviListener(epoch: Int): NaviHelperEventListener {
        val callbacks = SessionCallbackScope(
            isCurrent = { active && epoch == generation },
            enqueue = { callback -> main.post { callback() } },
        )
        return object : NaviHelperEventListener {
            override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
                callbacks.dispatch {
                    // Only the actual SDK callback advances this sequence; probes never inject arrivals.
                    val sequence = ++arrivalSeq
                    arrivalProof.sdkArrival(sequence)
                    probeLog("sdk_arrival arrivalSeq=$sequence elapsedMs=${SystemClock.elapsedRealtime()}")
                    // Arrival is NOT permission to display. The separate vehicle event controls UI.
                    emit(nativeEvent("arrival", "source" to "pleos-navi",
                        "name" to destinationArrivedInfo.name, "address" to destinationArrivedInfo.address,
                        "latitude" to null, "longitude" to null,
                        "simulated" to false, "arrivalSeq" to sequence))
                    refresh()
                }
            }
            override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
                callbacks.dispatch {
                    emit(nativeEvent("trip-invalidated", "source" to "pleos-navi", "reason" to "route_started"))
                    probeLog("sdk_route_started")
                }
            }
            override fun onRoutingCompleted() {
                callbacks.dispatch { probeLog("sdk_routing_completed") }
            }
            override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
                callbacks.dispatch {
                    val latitude = currentLocationInfo.latitude
                    val longitude = currentLocationInfo.longitude
                    fun near(lat: Double, lon: Double) = kotlin.math.abs(latitude - lat) < 0.0003 && kotlin.math.abs(longitude - lon) < 0.0003
                    probeLog("sdk_location finite=${latitude.isFinite() && longitude.isFinite()} atTestStart=${near(37.4830, 127.0250)} atTestDestination=${near(37.48544722, 127.03636666)}")
                }
            }
            override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
                callbacks.dispatch { probeLog("sdk_route_state naviMode=${routeStateInfo.naviMode} isRouting=${routeStateInfo.isRouting} termsAgreement=${routeStateInfo.termsAgreement}") }
            }
            override fun onDestinationChanged() {
                callbacks.dispatch { if (naviConnected) requestDestination() }
            }
            override fun onDestinationInfo(destinationInfo: DestinationInfo) {
                callbacks.dispatch {
                    emit(nativeEvent("destination", "source" to "pleos-navi",
                        "name" to destinationInfo.name, "address" to destinationInfo.address,
                        "latitude" to destinationInfo.latitude, "longitude" to destinationInfo.longitude))
                }
            }
            override fun onError(errorCode: NaviErrorCode) {
                callbacks.dispatch {
                    probeLog("sdk_error code=${errorCode.name}")
                    status("navigation_error:${errorCode.name}")
                }
            }
        }
    }

    private fun createUxListener(callbacks: SessionCallbackScope) = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { value ->
        callbacks.dispatch {
            if (restrictions?.activeRestrictions == value.activeRestrictions &&
                restrictions?.isRequiresDistractionOptimization == value.isRequiresDistractionOptimization) return@dispatch
            restrictions = value
            vehicleRevision++
            readAt = 0L
            publishGate()
            refresh()
        }
    }
    private fun createVehicleListener(callbacks: SessionCallbackScope) = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            callbacks.dispatch {
                if (value.status != CarPropertyValue.STATUS_AVAILABLE) {
                    invalidateVehicle("vehicle_property_unavailable")
                    return@dispatch
                }
                // Changes immediately close the gate. A fresh paired read confirms opening.
                when (value.propertyId) {
                    VehiclePropertyIds.GEAR_SELECTION -> {
                        val next = (value.value as? Int)?.let { it == VehicleGear.GEAR_PARK }
                        if (next != null && gearIsPark == next) return@dispatch
                        gearIsPark = next
                    }
                    VehiclePropertyIds.PERF_VEHICLE_SPEED -> {
                        val next = value.value as? Float
                        if (next != null && next.isFinite() && speed == next) return@dispatch
                        speed = next
                    }
                }
                vehicleRevision++
                readAt = 0L
                publishGate()
                refresh()
            }
        }
        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            callbacks.dispatch { invalidateVehicle("vehicle_property_error:$propertyId") }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            vehicleSamples.expireIfDue(arrivalSeq)
            refresh()
            main.postDelayed(this, 2_000L)
        }
    }

    override fun start() {
        if (closed) return
        if (active) {
            if (!vehicleSetupFailed && !navigationSetupFailed) { refresh(); return }
            stop()
        }
        active = true
        if (BuildConfig.VEHICLE_SAMPLE_AUDIT) vehicleSamples.start()
        vehicleSetupFailed = false
        navigationSetupFailed = false
        val epoch = ++generation
        status("connecting")
        publishGate()
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            status("automotive_platform_missing")
            return
        }
        try {
            val attempt = navigationAttempts.begin()
            navigationTimeout = Runnable {
                if (active && epoch == generation && navigationAttempts.complete(attempt)) {
                    naviConnected = false
                    navigationSetupFailed = true
                    status("navigation_connection_timeout")
                }
            }.also { main.postDelayed(it, 10_000L) }
            val listener = createNaviListener(epoch)
            naviListener = listener
            navi = NaviHelper(context.applicationContext).also { helper ->
                helper.initialize(
                    onConnected = {
                        main.post {
                            if (!active || epoch != generation || !navigationAttempts.complete(attempt)) return@post
                            navigationTimeout?.let(main::removeCallbacks)
                            navigationTimeout = null
                            try {
                                helper.addListener(listener)
                                naviConnected = true
                                navigationSetupFailed = false
                                requestDestination()
                                status("navigation_connected")
                            } catch (error: Exception) {
                                naviConnected = false
                                navigationSetupFailed = true
                                status("navigation_listener_failed:${error.javaClass.simpleName}")
                            }
                        }
                    },
                    onFailed = { error -> main.post {
                        if (active && epoch == generation && navigationAttempts.complete(attempt)) {
                            navigationTimeout?.let(main::removeCallbacks)
                            navigationTimeout = null
                            naviConnected = false
                            navigationSetupFailed = true
                            status("navigation_connection_failed:${error.javaClass.simpleName}")
                        }
                    } },
                )
            }
        } catch (error: Exception) {
            navigationAttempts.cancel()
            navigationTimeout?.let(main::removeCallbacks)
            navigationTimeout = null
            navigationSetupFailed = true
            status("navigation_init_failed:${error.javaClass.simpleName}")
        }
        try {
            car = Car.createCar(context, main, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { service, ready ->
                main.post {
                    if (!active || epoch != generation) return@post
                    val connection = ++carConnection
                    vehicleRead++
                    reading = false
                    connected = false
                    if (!ready) {
                        properties = null
                        ux = null
                        vehicleListener = null
                        uxRegistrationOwner = null
                        restrictions = null
                        invalidateVehicle("car_service_disconnected")
                        return@post
                    }
                    car = service
                    invalidateVehicle("vehicle_connecting")
                    val callbacks = SessionCallbackScope(
                        isCurrent = { active && epoch == generation && connection == carConnection },
                        enqueue = { callback -> main.post { callback() } },
                    )
                    val connectionUxListener = createUxListener(callbacks)
                    val connectionVehicleListener = createVehicleListener(callbacks)
                    val registrationOwner = Any()
                    // CarService can stall during emulator recovery. Never block the UI/Navi callback loop on IPC.
                    val accepted = reader.tryExecute {
                        var localUx: CarUxRestrictionsManager? = null
                        var localProperties: CarPropertyManager? = null
                        try {
                            val managerUx = service.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
                            val managerProperties = service.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                            localUx = managerUx; localProperties = managerProperties
                            val initialRestrictions = managerUx.currentCarUxRestrictions
                            if (!VehicleUxRegistrations.forManager(managerUx).register(registrationOwner,
                                    { active && epoch == generation && connection == carConnection },
                                    {
                                        // AAOS registerListener ignores replacement while one is already present.
                                        managerUx.unregisterListener()
                                        managerUx.registerListener(connectionUxListener)
                                    })) return@tryExecute
                            for (id in listOf(VehiclePropertyIds.GEAR_SELECTION, VehiclePropertyIds.PERF_VEHICLE_SPEED)) {
                                requireNotNull(managerProperties.getCarPropertyConfig(id)) { "Vehicle property not supported" }
                                check(managerProperties.registerCallback(connectionVehicleListener, id, CarPropertyManager.SENSOR_RATE_ONCHANGE)) {
                                    "Vehicle property subscription rejected"
                                }
                            }
                            main.post {
                                if (active && epoch == generation && connection == carConnection) {
                                    ux = managerUx; properties = managerProperties; restrictions = initialRestrictions
                                    vehicleListener = connectionVehicleListener
                                    uxRegistrationOwner = registrationOwner
                                    connected = true
                                    vehicleSetupFailed = false
                                    refresh()
                                    status("vehicle_connected")
                                } else {
                                    reader.tryExecute {
                                        runCatching { VehicleUxRegistrations.forManager(managerUx).remove(registrationOwner) { managerUx.unregisterListener() } }
                                        runCatching { managerProperties.unregisterCallback(connectionVehicleListener) }
                                    }
                                }
                            }
                        } catch (error: Exception) {
                            runCatching { localUx?.let { manager ->
                                VehicleUxRegistrations.forManager(manager).remove(registrationOwner) { manager.unregisterListener() }
                            } }
                            runCatching { localProperties?.unregisterCallback(connectionVehicleListener) }
                            main.post {
                                if (active && epoch == generation && connection == carConnection) {
                                    connected = false
                                    vehicleSetupFailed = true
                                    invalidateVehicle("vehicle_access_failed:${error.javaClass.simpleName}")
                                }
                            }
                        }
                    }
                    if (!accepted) {
                        vehicleSetupFailed = true
                        invalidateVehicle("vehicle_worker_capacity_exhausted")
                        status("vehicle_worker_capacity_exhausted")
                    }
                    main.postDelayed({
                        if (active && epoch == generation && connection == carConnection && !connected) {
                            vehicleSetupFailed = true
                            invalidateVehicle("vehicle_connection_timeout")
                        }
                    }, 5_000)
                }
            }
        } catch (error: Exception) {
            vehicleSetupFailed = true
            invalidateVehicle("car_service_failed:${error.javaClass.simpleName}")
        }
        main.post(tick)
    }

    private fun requestDestination() {
        try { navi?.getDestinationInfo() }
        catch (error: Exception) { status("destination_query_failed:${error.javaClass.simpleName}") }
    }

    private fun probeLog(message: String) {
        if (BuildConfig.DEBUG) Log.i("MajoongProbe", message)
    }

    override fun debugProbe(command: String) {
        if (!BuildConfig.DEBUG) return
        main.post {
            val helper = navi
            if (!active || !naviConnected || helper == null) {
                probeLog("probe_unavailable command=$command active=$active navigationConnected=$naviConnected")
                status("debug_probe_navigation_not_connected")
                return@post
            }
            try {
                when (command) {
                    "observe" -> {
                        helper.getCurrentLocationInfo()
                        helper.getRouteStateInfo()
                        probeLog("observe_requested")
                    }
                    "route_sample" -> {
                        // Public Pleos sample POI. This requests real routing; it does not fake a callback.
                        helper.requestRoute(RouteInfo(
                            latitude = 37.48544722,
                            longitude = 127.03636666,
                            displayLatitude = null,
                            displayLongitude = null,
                            poiName = "42dot",
                            poiId = "36khp37hh8gqcm5nqhe5",
                            address = "서울시 강남구 남부순환로 2621",
                            poiSubId = "0",
                            routeOption = RouteOption.RECOMMENDED,
                            poiProvider = "",
                            isMobileRequest = false,
                        ))
                        probeLog("route_sample_requested latitude=37.48544722 longitude=127.03636666")
                    }
                    else -> probeLog("probe_unknown_command")
                }
            } catch (error: Exception) {
                probeLog("probe_failed command=$command error=${error.javaClass.simpleName}")
                status("debug_probe_failed:${error.javaClass.simpleName}")
            }
        }
    }

    override fun refresh() = refreshVehicle(allowCapacityRetry = true)

    private fun refreshVehicle(allowCapacityRetry: Boolean) {
        publishGate()
        val manager = properties ?: return
        if (!active || !connected || reading) return
        reading = true
        val read = ++vehicleRead
        val window = VehicleReadWindow(SystemClock.elapsedRealtime())
        val epoch = generation
        val connection = carConnection
        val revision = vehicleRevision
        val uxManager = ux
        val accepted = reader.tryExecute {
            try {
                val gear = manager.getProperty(Int::class.javaObjectType, VehiclePropertyIds.GEAR_SELECTION, 0)
                val velocity = manager.getProperty(Float::class.javaObjectType, VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)
                check(gear.status == CarPropertyValue.STATUS_AVAILABLE && velocity.status == CarPropertyValue.STATUS_AVAILABLE)
                val currentUx = uxManager?.currentCarUxRestrictions
                main.post {
                    if (active && epoch == generation && connection == carConnection && read == vehicleRead) {
                        if (!window.accepts(SystemClock.elapsedRealtime())) {
                            failVehicleReadTimeout()
                            return@post
                        }
                        reading = false
                        if (revision != vehicleRevision) {
                            // A newer callback invalidated this snapshot while the paired read ran.
                            refresh()
                            return@post
                        }
                        gearIsPark = gear.value == VehicleGear.GEAR_PARK
                        speed = velocity.value
                        restrictions = currentUx
                        readAt = window.startedAt
                        vehicleSamples.acceptedRead(arrivalSeq, readAt, speed, gearIsPark)
                        publishGate()
                    }
                }
            } catch (error: Exception) {
                main.post {
                    if (active && epoch == generation && connection == carConnection && read == vehicleRead) {
                        reading = false
                        invalidateVehicle("vehicle_read_failed:${error.javaClass.simpleName}")
                    }
                }
            }
        }
        if (!accepted) {
            if (allowCapacityRetry) {
                // A setup callback can reach main just before its worker returns. Allow one handoff retry.
                invalidateVehicle("vehicle_worker_busy")
                main.postDelayed({
                    if (active && epoch == generation && connection == carConnection && read == vehicleRead) {
                        reading = false
                        refreshVehicle(allowCapacityRetry = false)
                    }
                }, 100L)
                return
            }
            reading = false
            connected = false
            vehicleSetupFailed = true
            invalidateVehicle("vehicle_worker_capacity_exhausted")
            status("vehicle_worker_capacity_exhausted")
            return
        }
        main.postDelayed({
            if (active && epoch == generation && connection == carConnection && read == vehicleRead && reading) {
                failVehicleReadTimeout()
                // No repeated worker replacement. A new Car lifecycle connection or user retry may recover.
            }
        }, 5_000L)
    }

    private fun failVehicleReadTimeout() {
        vehicleRead++ // Includes results queued before the timer but delivered after their deadline.
        reading = false
        connected = false
        vehicleSetupFailed = true
        invalidateVehicle("vehicle_read_timeout")
        status("vehicle_read_timeout")
    }

    private fun invalidateVehicle(reason: String) {
        vehicleSamples.unavailable(arrivalSeq)
        vehicleRevision++
        gearIsPark = null
        speed = null
        readAt = 0L
        publishGate(reason)
    }

    private fun currentDecision(): ParkedGate.Decision {
        val current = restrictions
        return ParkedGate.evaluate(ParkedGate.Input(
            connected, gearIsPark, speed, current != null,
            current?.isRequiresDistractionOptimization ?: true,
            current?.activeRestrictions ?: -1,
            readAt > 0 && SystemClock.elapsedRealtime() - readAt <= 5_000,
        ))
    }

    override fun isDisplayAllowed(): Boolean = currentDecision().allowed

    private fun publishGate(overrideReason: String? = null) {
        val current = restrictions
        val decision = currentDecision()
        // Pending normal callback reads do not invent new samples. True unavailability or expiry is explicit.
        if (!connected || (readAt > 0 && SystemClock.elapsedRealtime() - readAt > 3_000L)) vehicleSamples.unavailable(arrivalSeq)
        arrivalProof.gate(arrivalSeq, gearIsPark,
            speed?.takeIf { it.isFinite() }?.let { kotlin.math.abs(it) <= 0.05f },
            current?.let { !it.isRequiresDistractionOptimization && it.activeRestrictions == 0 },
            readAt > 0 && SystemClock.elapsedRealtime() - readAt <= 5_000, decision.allowed)
        emit(nativeEvent("vehicle", "source" to "aaos-car-service", "displayAllowed" to decision.allowed,
            "reason" to (overrideReason ?: decision.reason), "gear" to (if (gearIsPark == true) "P" else if (gearIsPark == false) "NOT_P" else "UNKNOWN"),
            "speedMetersPerSecond" to speed, "activeRestrictions" to current?.activeRestrictions,
            "requiresDistractionOptimization" to current?.isRequiresDistractionOptimization,
            "simulated" to false))
    }

    private fun status(message: String) = emit(nativeEvent("status", "platform" to "pleos",
        "connected" to naviConnected, "vehicleConnected" to connected, "monitoring" to active,
        "sdk" to "NaviHelper 3.0.5", "message" to message,
        "backgroundGuaranteed" to false))

    override fun stop() {
        if (closed) return
        active = false
        navigationAttempts.cancel()
        navigationTimeout?.let(main::removeCallbacks)
        navigationTimeout = null
        vehicleSamples.stop(arrivalSeq)
        generation++
        carConnection++
        vehicleRead++
        main.removeCallbacks(tick)
        val oldListener = naviListener
        runCatching { if (oldListener != null) navi?.removeListener(oldListener) }
        runCatching { navi?.release() }
        naviListener = null
        val oldUx = ux; val oldProperties = properties; val oldCar = car; val oldVehicleListener = vehicleListener
        val oldUxOwner = uxRegistrationOwner
        reader.tryExecute {
            runCatching { if (oldUx != null && oldUxOwner != null) {
                VehicleUxRegistrations.forManager(oldUx).remove(oldUxOwner) { oldUx.unregisterListener() }
            } }
            runCatching { if (oldVehicleListener != null) oldProperties?.unregisterCallback(oldVehicleListener) }
            runCatching { oldCar?.disconnect() }
        }
        navi = null; car = null; ux = null; properties = null
        vehicleListener = null
        uxRegistrationOwner = null
        connected = false; naviConnected = false; restrictions = null; reading = false
        invalidateVehicle("monitoring_stopped")
        status("stopped")
    }

    override fun close() {
        if (closed) return
        stop()
        closed = true
        // Process-wide bounded workers survive Service recreation; a blocked Binder call is not cancellable.
    }
}
