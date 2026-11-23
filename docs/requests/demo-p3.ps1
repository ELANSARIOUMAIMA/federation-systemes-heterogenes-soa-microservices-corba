param(
    [string]$Gateway = "http://localhost:8080",
    [string]$RailBridge = "http://localhost:8082",
    [string]$AirBridge = "http://localhost:8083",
    [string]$Jaeger = "http://localhost:16686",
    [switch]$SkipFailover
)

$ErrorActionPreference = "Stop"

$user = if ([string]::IsNullOrWhiteSpace($env:GATEWAY_USERNAME)) { "demo" } else { $env:GATEWAY_USERNAME }
$pass = if ([string]::IsNullOrWhiteSpace($env:GATEWAY_PASSWORD)) { "demo-pass" } else { $env:GATEWAY_PASSWORD }
$basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${user}:${pass}"))
$authHeaders = @{ Authorization = "Basic $basic" }
$runId = [guid]::NewGuid().ToString("N").Substring(0, 8)

function Assert-Equal($Actual, $Expected, $Message) {
    if ($Actual -ne $Expected) { throw "$Message (attendu=$Expected, recu=$Actual)" }
}

Write-Host "=== [1/6] Sante & circuit breakers initiaux ==="
$gwHealth = Invoke-RestMethod "$Gateway/actuator/health"
$railHealth = Invoke-RestMethod "$RailBridge/actuator/health"
$airHealth = Invoke-RestMethod "$AirBridge/actuator/health"
Assert-Equal $railHealth.components.railCorbaCircuitBreaker.details.state "CLOSED" "rail-bridge CB doit etre CLOSED"
Assert-Equal $airHealth.components.airSoapCircuitBreaker.details.state "CLOSED" "air-bridge CB doit etre CLOSED"
Write-Host "  OK: gateway=$($gwHealth.status), rail-bridge=CLOSED, air-bridge=CLOSED"

Write-Host "=== [2/6] Recherche multimodale agregee ==="
$search = Invoke-RestMethod -Uri "$Gateway/api/v1/search?origin=Casablanca&destination=Rabat&date=2026-11-15" -Headers $authHeaders
$modes = @($search | ForEach-Object mode)
if (-not ($modes -contains "TRAIN") -or -not ($modes -contains "CAR")) {
    throw "La recherche doit contenir TRAIN et CAR (recu: $($modes -join ','))"
}
Write-Host "  OK: $(@($search).Count) offres retournees (modes: $(($modes | Select-Object -Unique) -join ', '))"

Write-Host "=== [3/6] Saga nominale 3 modes (TRAIN + AIR + CAR) + Idempotence ==="
$nomKey = "demo-p3-ok-$runId"
$nomBody = @{
    segments = @(
        @{ mode = "TRAIN"; offerId = "RAIL:RAIL-CAS-RAB-0730"; origin = "Casablanca"; destination = "Rabat"; date = "2026-11-15"; seats = 1 },
        @{ mode = "AIR"; offerId = "AIR:AIR-RBA-ORY-1200"; origin = "Rabat"; destination = "Paris"; date = "2026-11-15"; seats = 1 },
        @{ mode = "CAR"; offerId = "CAR:CAR-CAS-RAB-1000"; origin = "Casablanca"; destination = "Rabat"; date = "2026-11-15"; seats = 1 }
    )
} | ConvertTo-Json -Depth 6
$nomHeaders = @{ Authorization = "Basic $basic"; "Idempotency-Key" = $nomKey }
$booking = Invoke-RestMethod -Uri "$Gateway/api/v1/bookings" -Method Post -Headers $nomHeaders -ContentType "application/json" -Body $nomBody
Assert-Equal $booking.state "CONFIRMED" "La reservation 3 modes doit etre CONFIRMED"
Assert-Equal $booking.totalMinor 183475 "Total 3 modes attendu (MAD centimes)"

$replay = Invoke-RestMethod -Uri "$Gateway/api/v1/bookings" -Method Post -Headers $nomHeaders -ContentType "application/json" -Body $nomBody
Assert-Equal $replay.bookingId $booking.bookingId "Le rejeu idempotent doit renvoyer le meme bookingId"

$conflictStatus = 0
try {
    $conflictBody = '{"segments":[{"mode":"TRAIN","offerId":"RAIL:RAIL-CAS-RAB-0730","origin":"Casablanca","destination":"Rabat","date":"2026-11-15","seats":2}]}'
    Invoke-RestMethod -Uri "$Gateway/api/v1/bookings" -Method Post -Headers $nomHeaders -ContentType "application/json" -Body $conflictBody | Out-Null
} catch {
    $conflictStatus = [int]$_.Exception.Response.StatusCode
}
Assert-Equal $conflictStatus 409 "Meme cle avec payload different doit renvoyer 409"
Write-Host "  OK: bookingId=$($booking.bookingId) CONFIRMED (183475 centimes), rejeu identique, conflit -> 409"

Write-Host "=== [4/6] Saga en echec compense (TRAIN + CAR + AIR-CMN-MAD-1800 x 4 > 3 places) ==="
$failBody = @{
    segments = @(
        @{ mode = "TRAIN"; offerId = "RAIL:RAIL-CAS-RAB-0730"; origin = "Casablanca"; destination = "Rabat"; date = "2026-11-15"; seats = 1 },
        @{ mode = "CAR"; offerId = "CAR:CAR-CAS-RAB-1000"; origin = "Casablanca"; destination = "Rabat"; date = "2026-11-15"; seats = 1 },
        @{ mode = "AIR"; offerId = "AIR:AIR-CMN-MAD-1800"; origin = "Casablanca"; destination = "Madrid"; date = "2026-11-15"; seats = 4 }
    )
} | ConvertTo-Json -Depth 6
$failHeaders = @{ Authorization = "Basic $basic"; "Idempotency-Key" = "demo-p3-fail-$runId" }
$failed = Invoke-RestMethod -Uri "$Gateway/api/v1/bookings" -Method Post -Headers $failHeaders -ContentType "application/json" -Body $failBody
Assert-Equal $failed.state "CANCELLED" "L'echec doit compenser et terminer CANCELLED"
Assert-Equal $failed.steps[0].state "COMPENSATED" "Le segment TRAIN doit etre COMPENSATED"
Assert-Equal $failed.steps[1].state "COMPENSATED" "Le segment CAR doit etre COMPENSATED"
Assert-Equal $failed.steps[2].state "HOLD_FAILED" "Le segment AIR doit etre HOLD_FAILED"
$airHealthAfter409 = Invoke-RestMethod "$AirBridge/actuator/health"
Assert-Equal $airHealthAfter409.components.airSoapCircuitBreaker.details.state "CLOSED" "Un 409 metier ne doit jamais ouvrir le circuit"
Write-Host "  OK: bookingId=$($failed.bookingId) CANCELLED (TRAIN+CAR COMPENSATED, air-bridge CB reste CLOSED)"

Write-Host "=== [5/6] Verification des traces distribuees dans Jaeger ==="
Start-Sleep -Seconds 6
$services = (Invoke-RestMethod "$Jaeger/api/services").data
$traces = (Invoke-RestMethod "$Jaeger/api/traces?service=api-gateway&operation=POST%20/api/v1/bookings&limit=10").data
$fullTrace = $traces | Where-Object {
    $ops = $_.spans | ForEach-Object operationName
    ($ops -contains "CORBA RailBooking/hold") -and ($ops -contains "SOAP AirPartner/holdFlight")
} | Select-Object -First 1
if ($null -eq $fullTrace) {
    throw "Aucune trace Jaeger complete avec spans CORBA et SOAP trouvee."
}
$traceServices = @($fullTrace.processes.PSObject.Properties | ForEach-Object { $_.Value.serviceName } | Select-Object -Unique)
Write-Host "  OK: $($services.Count) services dans Jaeger ($($services -join ', '))"
Write-Host "  OK: trace $($fullTrace.traceID) -> $($fullTrace.spans.Count) spans sur $($traceServices.Count) services (CORBA + SOAP + Outbox + Notification)"
Write-Host "  UI Jaeger : $Jaeger/trace/$($fullTrace.traceID)"

if (-not $SkipFailover) {
    Write-Host "=== [6/6] Demonstration de resilience : arret de rail -> circuit OPEN -> reprise ==="
    try {
        docker compose stop rail | Out-Null
        for ($i = 1; $i -le 4; $i++) {
            $sw = [Diagnostics.Stopwatch]::StartNew()
            $resp = Invoke-WebRequest -UseBasicParsing -Uri "$Gateway/api/v1/search?origin=Casablanca&destination=Rabat&date=2026-11-15" -Headers $authHeaders
            $sw.Stop()
            Write-Host "  Recherche #$i (rail arrete) : HTTP=$($resp.StatusCode) temps=$($sw.ElapsedMilliseconds)ms Partial=$($resp.Headers['X-Partial-Results']) Reason=$($resp.Headers['X-Degraded-Reason'])"
        }
        $railDownHealth = Invoke-RestMethod "$RailBridge/actuator/health"
        $gwDownHealth = Invoke-RestMethod "$Gateway/actuator/health"
        $railCb = $railDownHealth.components.railCorbaCircuitBreaker.details.state
        $gwCb = $gwDownHealth.components.gatewayCircuitBreakers.details.rail.state
        Write-Host "  Etat circuits : rail-bridge=$railCb, api-gateway(rail)=$gwCb"
        if ($railCb -ne "OPEN" -and $gwCb -ne "OPEN") {
            throw "Au moins un circuit breaker rail doit etre OPEN pendant la panne."
        }
    } finally {
        docker compose start rail | Out-Null
    }
    Write-Host "  Attente de la fenetre HALF_OPEN (17 s) et reprise automatique..."
    Start-Sleep -Seconds 17
    $recovered = Invoke-RestMethod -Uri "$Gateway/api/v1/search?origin=Casablanca&destination=Rabat&date=2026-11-15" -Headers $authHeaders
    $recovered = Invoke-RestMethod -Uri "$Gateway/api/v1/search?origin=Casablanca&destination=Rabat&date=2026-11-15" -Headers $authHeaders
    $recModes = @($recovered | ForEach-Object mode)
    if (-not ($recModes -contains "TRAIN")) {
        throw "Les offres TRAIN doivent revenir apres redemarrage de rail."
    }
    Write-Host "  OK: rail-bridge a relu l'IOR, circuit referme, offres TRAIN de retour ($(@($recovered).Count) offres)"
}

Write-Host ""
Write-Host "=== DEMO P3 COMPLETE REUSSIE ==="
