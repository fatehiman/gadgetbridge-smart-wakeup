<?php

use App\Http\Controllers\Api\ConfigController;
use App\Http\Controllers\Api\ExportApiController;
use App\Http\Controllers\Api\IngestController;
use Illuminate\Support\Facades\Route;

Route::prefix('v1')->group(function () {
    // phone (ingest token) or analysis (analyze token)
    Route::middleware(['api.token:ingest,analyze', 'throttle:120,1'])->group(function () {
        Route::get('ping', [IngestController::class, 'ping']);
        Route::get('config', [ConfigController::class, 'show']);
    });
    // phone only
    Route::post('ingest', [IngestController::class, 'ingest'])->middleware(['api.token:ingest', 'throttle:120,1']);
    // analysis only
    Route::middleware(['api.token:analyze', 'throttle:60,1'])->group(function () {
        Route::get('export', [ExportApiController::class, 'show']);
        Route::get('config/history', [ConfigController::class, 'history']);
        Route::put('config', [ConfigController::class, 'store']);
    });
});
