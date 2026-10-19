<?php

use App\Http\Controllers\AuthController;
use App\Http\Controllers\ExportController;
use App\Http\Controllers\NightController;
use App\Http\Controllers\SettingsController;
use App\Http\Controllers\TrendController;
use Illuminate\Support\Facades\Route;

Route::middleware('guest')->group(function () {
    Route::get('login', [AuthController::class, 'showLogin'])->name('login');
    Route::post('login', [AuthController::class, 'login'])->middleware('throttle:10,1');
    Route::get('register', [AuthController::class, 'showRegister'])->name('register');
    Route::post('register', [AuthController::class, 'register'])->middleware('throttle:10,1');
});

Route::middleware('auth')->group(function () {
    Route::post('logout', [AuthController::class, 'logout'])->name('logout');
    Route::get('/', [NightController::class, 'index'])->name('nights');
    Route::get('nights/{session}', [NightController::class, 'show'])->name('nights.show');
    Route::post('nights/{session}', [NightController::class, 'update'])->name('nights.update');
    Route::get('trends', [TrendController::class, 'index'])->name('trends');
    Route::get('export', [ExportController::class, 'index'])->name('export');
    Route::get('export/download', [ExportController::class, 'download'])->name('export.download');
    Route::get('settings', [SettingsController::class, 'index'])->name('settings');
    Route::post('settings', [SettingsController::class, 'save'])->name('settings.save');
    Route::post('settings/cleanup', [SettingsController::class, 'cleanup'])->name('settings.cleanup');
    Route::post('settings/config', [SettingsController::class, 'saveConfig'])->name('settings.config');
    Route::post('settings/tokens', [SettingsController::class, 'createToken'])->name('tokens.create');
    Route::post('settings/tokens/{id}/revoke', [SettingsController::class, 'revokeToken'])->name('tokens.revoke');
});
