<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        // history of detector configs. The phone downloads the newest one; every night stores the version it used.
        Schema::create('detector_configs', function (Blueprint $t) {
            $t->id();
            $t->timestamp('created_at')->useCurrent();
            $t->string('note', 1000)->nullable();
            $t->json('config');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('detector_configs');
    }
};
