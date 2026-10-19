<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('devices', function (Blueprint $t) {
            $t->id();
            $t->string('external_id')->unique();
            $t->string('name')->nullable();
            $t->string('model')->nullable();
            $t->string('firmware')->nullable();
            $t->timestamp('last_seen_at')->nullable();
            $t->timestamps();
        });

        Schema::create('sleep_sessions', function (Blueprint $t) {
            $t->id();
            $t->string('uuid', 64)->unique();
            $t->foreignId('device_id')->constrained()->cascadeOnDelete();
            $t->unsignedBigInteger('started_at')->index();   // epoch seconds (UTC)
            $t->unsignedBigInteger('ended_at')->nullable();
            $t->string('mode', 32)->nullable();
            $t->unsignedBigInteger('target_time')->nullable();
            $t->unsignedSmallInteger('tolerance_min')->nullable();
            $t->unsignedBigInteger('window_start')->nullable();
            $t->unsignedBigInteger('deadline')->nullable();
            $t->string('detector_id', 64)->nullable();
            $t->json('detector_params')->nullable();
            $t->string('timezone', 64)->nullable();
            $t->string('wake_reason', 32)->nullable();
            $t->unsignedTinyInteger('morning_rating')->nullable();
            $t->text('notes')->nullable();
            $t->timestamps();
        });

        Schema::create('samples', function (Blueprint $t) {
            $t->id();
            $t->foreignId('device_id')->constrained()->cascadeOnDelete();
            $t->unsignedBigInteger('ts');                    // epoch seconds (UTC)
            $t->smallInteger('heart_rate')->nullable();
            $t->integer('steps')->nullable();
            $t->integer('intensity')->nullable();
            $t->integer('movement')->nullable();
            $t->smallInteger('spo2')->nullable();
            $t->string('sleep_stage', 32)->nullable();       // as reported by the watch
            $t->json('extra')->nullable();
            $t->unique(['device_id', 'ts']);                 // also serves range queries
            $t->index('ts');                                 // cleanup
        });

        Schema::create('events', function (Blueprint $t) {
            $t->id();
            $t->foreignId('session_id')->constrained('sleep_sessions')->cascadeOnDelete();
            $t->foreignId('device_id')->constrained()->cascadeOnDelete();
            $t->unsignedBigInteger('ts');                    // epoch milliseconds
            $t->string('type', 48);
            $t->json('payload')->nullable();
            $t->unique(['session_id', 'ts', 'type']);
            $t->index('ts');
        });

        Schema::create('api_tokens', function (Blueprint $t) {
            $t->id();
            $t->string('name');
            $t->string('token_hash', 64)->unique();          // sha256 of the plain token
            $t->string('ability', 32)->default('ingest');
            $t->timestamp('last_used_at')->nullable();
            $t->timestamp('revoked_at')->nullable();
            $t->timestamps();
        });

        Schema::create('ingest_logs', function (Blueprint $t) {
            $t->id();
            $t->timestamp('created_at')->useCurrent()->index();
            $t->unsignedBigInteger('api_token_id')->nullable();
            $t->string('ip', 45)->nullable();
            $t->unsignedSmallInteger('status');
            $t->unsignedInteger('sessions')->default(0);
            $t->unsignedInteger('samples')->default(0);
            $t->unsignedInteger('events')->default(0);
            $t->unsignedInteger('duplicates')->default(0);
            $t->string('message', 500)->nullable();
        });

        Schema::create('app_settings', function (Blueprint $t) {
            $t->string('key', 64)->primary();
            $t->text('value')->nullable();
        });
    }

    public function down(): void
    {
        foreach (['app_settings', 'ingest_logs', 'api_tokens', 'events', 'samples', 'sleep_sessions', 'devices'] as $n) {
            Schema::dropIfExists($n);
        }
    }
};
