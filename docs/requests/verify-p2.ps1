param(
    [string]$Gateway = "http://localhost:8080",
    [string]$Carshare = "http://localhost:8084",
    [string]$RailBridge = "http://localhost:8082",
    [string]$AirBridge = "http://localhost:8083"
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($env:GATEWAY_USERNAME) -or
    [string]::IsNullOrWhiteSpace($env:GATEWAY_PASSWORD)) {
    throw "Définir GATEWAY_USERNAME et GATEWAY_PASSWORD avant la démonstration."
}

$basic = [Convert]::ToBase64String(
    [Text.Encoding]::ASCII.GetBytes("$($env:GATEWAY_USERNAME):$($env:GATEWAY_PASSWORD)"))
$gatewayHeaders = @{ Authorization = "Basic $basic" }
$runId = [guid]::NewGuid().ToString("N")

function Assert-Equal($Actual, $Expected, $Message) {
    if ($Actual -ne $Expected) {
        throw "$Message (attendu=$Expected, reçu=$Actual)"
    }
}

function Assert-HttpStatus($Uri, $Method, $Body, $Headers, $Expected) {
    $failure = $null
    try {
        Invoke-RestMethod -Uri $Uri -Method $Method -Headers $Headers `
            -ContentType "application/json" -Body $Body | Out-Null
    } catch {
        $failure = $_
    }
    if ($null -eq $failure) {
        throw "HTTP $Expected attendu pour $Uri, mais la requête a réussi."
    }
    $actual = [int]$failure.Exception.Response.StatusCode
    Assert-Equal $actual $Expected "Statut inattendu pour $Uri"
}

function Wait-Endpoint($Uri, $Headers, $AllowedStatus) {
    $lastError = $null
    for ($attempt = 1; $attempt -le 60; $attempt++) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -Headers $Headers -TimeoutSec 3
            if ($AllowedStatus -contains [int]$response.StatusCode) { return }
            $lastError = "HTTP $($response.StatusCode)"
        } catch {
            $statusCode = $null
            if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
                $statusCode = [int]$_.Exception.Response.StatusCode
            }
            if ($null -ne $statusCode -and $AllowedStatus -contains $statusCode) { return }
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Seconds 2
    }
    throw "Délai de readiness dépassé pour $Uri; dernière erreur: $lastError"
}

$plainHeaders = @{ Authorization = "Basic $basic" }
Write-Host "Attente du démarrage des API P2 (maximum 120 secondes)"
Wait-Endpoint "$Carshare/v1/offers?origin=Casablanca&destination=Rabat&date=2026-11-15" @{} @(200)
Wait-Endpoint "http://localhost:8085/v1/pricing/quote" @{} @(405)
Wait-Endpoint "http://localhost:8086/v1/bookings/readiness-probe" @{} @(404)
Wait-Endpoint "$Gateway/api/v1/search?origin=Casablanca&destination=Rabat&date=2026-11-15" $plainHeaders @(200)

Write-Host "[1/6] Recherche agrégée Gateway"
$search = Invoke-RestMethod -Uri "$Gateway/api/v1/search?origin=Casablanca&destination=Rabat&date=2026-11-15" `
    -Headers $plainHeaders
if (@($search).Count -lt 2) { throw "La recherche attend au moins deux offres (train et car)." }
if (-not (@($search | ForEach-Object mode) -contains "TRAIN") -or
    -not (@($search | ForEach-Object mode) -contains "CAR")) {
    throw "La recherche agrégée ne contient pas TRAIN et CAR."
}

Write-Host "[2/6] Saga nominale + lecture persistée + idempotence"
$key = "p2-demo-$runId"
$bookingRequest = @{
    segments = @(
        @{ mode = "TRAIN"; offerId = "RAIL:RAIL-CAS-RAB-0730"; origin = "Casablanca"; destination = "Rabat"; date = "2026-11-15"; seats = 1 },
        @{ mode = "AIR"; offerId = "AIR:AIR-RBA-ORY-1200"; origin = "Rabat"; destination = "Paris"; date = "2026-11-15"; seats = 1 }
    )
}
$body = $bookingRequest | ConvertTo-Json -Depth 8
$headers = @{ Authorization = "Basic $basic"; "Idempotency-Key" = $key }
$booking = Invoke-RestMethod -Uri "$Gateway/api/v1/bookings" -Method Post -Headers $headers `
    -ContentType "application/json" -Body $body
Assert-Equal $booking.state "CONFIRMED" "La Saga nominale doit être confirmée"
Assert-Equal $booking.totalMinor 179000 "Total nominal attendu (MAD centimes)"
$readBack = Invoke-RestMethod -Uri "$Gateway/api/v1/bookings/$($booking.bookingId)" -Headers $gatewayHeaders
Assert-Equal $readBack.bookingId $booking.bookingId "La lecture doit retrouver le même dossier"
$replayed = Invoke-RestMethod -Uri "$Gateway/api/v1/bookings" -Method Post -Headers $headers `
    -ContentType "application/json" -Body $body
Assert-Equal $replayed.bookingId $booking.bookingId "L'idempotence doit réutiliser le dossier existant"

Write-Host "[3/6] Même clé avec payload différent -> 409"
$conflictingObject = $bookingRequest | ConvertTo-Json -Depth 8 | ConvertFrom-Json
$conflictingObject.segments[0].seats = 2
$conflictingBody = $conflictingObject | ConvertTo-Json -Depth 8
Assert-HttpStatus "$Gateway/api/v1/bookings" "Post" $conflictingBody $headers 409

Write-Host "[4/6] Échec du deuxième hold -> compensation du premier"
$failureRequest = @{
    segments = @(
        @{ mode = "TRAIN"; offerId = "RAIL:RAIL-CAS-RAB-0730"; origin = "Casablanca"; destination = "Rabat"; date = "2026-11-15"; seats = 1 },
        @{ mode = "AIR"; offerId = "AIR:AIR-CMN-MAD-1800"; origin = "Casablanca"; destination = "Madrid"; date = "2026-11-15"; seats = 4 }
    )
}
$failureHeaders = @{ Authorization = "Basic $basic"; "Idempotency-Key" = "p2-failure-$runId" }
$failed = Invoke-RestMethod -Uri "$Gateway/api/v1/bookings" -Method Post -Headers $failureHeaders `
    -ContentType "application/json" -Body ($failureRequest | ConvertTo-Json -Depth 8)
Assert-Equal $failed.state "CANCELLED" "L'échec de hold doit terminer après compensation"
if ($failed.steps[0].state -ne "COMPENSATED") { throw "Le hold antérieur n'a pas été compensé." }
Write-Host "  Note: l'itinéraire Casablanca-Rabat puis Casablanca-Madrid est volontairement non contigu; ce test simule un refus de stock."

Write-Host "[5/6] Cycle carshare + annulation rejouée"
$carOffer = @(Invoke-RestMethod -Uri "$Carshare/v1/offers?origin=Casablanca&destination=Rabat&date=2026-11-15")
$capacityBefore = $carOffer[0].seatsAvailable
$carHold = Invoke-RestMethod -Uri "$Carshare/v1/holds" -Method Post -ContentType "application/json" `
    -Body '{"offerId":"CAR:CAR-CAS-RAB-1000","seats":2}'
$carConfirm = Invoke-RestMethod -Uri "$Carshare/v1/holds/$($carHold.holdId)/confirm" -Method Post
Invoke-RestMethod -Uri "$Carshare/v1/references/$($carConfirm.bookingRef)" -Method Delete | Out-Null
Invoke-RestMethod -Uri "$Carshare/v1/references/$($carConfirm.bookingRef)" -Method Delete | Out-Null
$carOfferAfter = @(Invoke-RestMethod -Uri "$Carshare/v1/offers?origin=Casablanca&destination=Rabat&date=2026-11-15")
Assert-Equal $carOfferAfter[0].seatsAvailable $capacityBefore "L'annulation idempotente doit restituer exactement deux sièges"

Write-Host "[6/6] Bridges P1 toujours disponibles"
$railOffers = Invoke-RestMethod -Uri "$RailBridge/v1/offers?origin=Casablanca&destination=Rabat&date=2026-11-15"
$airOffers = Invoke-RestMethod -Uri "$AirBridge/v1/offers?origin=Rabat&destination=Paris&date=2026-11-15"
if (@($railOffers).Count -eq 0 -or @($airOffers).Count -eq 0) { throw "Un bridge P1 ne retourne aucune offre." }

Write-Host "P2: scénarios Gateway, Saga, idempotence, compensation, carshare et régression P1 réussis."
Write-Host "Événements: docker compose logs --since 2m notification-service"
