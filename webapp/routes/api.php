<?php

use App\Http\Controllers\Api\IngestController;
use Illuminate\Support\Facades\Route;

Route::prefix('v1')->middleware(['api.token:ingest', 'throttle:120,1'])->group(function () {
    Route::get('ping', [IngestController::class, 'ping']);
    Route::post('ingest', [IngestController::class, 'ingest']);
});
