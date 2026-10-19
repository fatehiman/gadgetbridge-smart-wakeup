<?php

use App\Support\Cleanup;
use Illuminate\Support\Facades\Artisan;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Hash;
use Illuminate\Support\Facades\Schedule;
use Illuminate\Support\Str;

Artisan::command('gadget:cleanup {--data-days=} {--log-days=}', function () {
    $r = Cleanup::run(
        $this->option('data-days') !== null ? (int) $this->option('data-days') : null,
        $this->option('log-days') !== null ? (int) $this->option('log-days') : null,
    );
    $this->info('Deleted: '.json_encode($r));
})->purpose('Delete old data and logs (days come from the settings page)');

Artisan::command('gadget:token {name=phone} {--revoke= : revoke the token with this id}', function (string $name) {
    if ($id = $this->option('revoke')) {
        DB::table('api_tokens')->where('id', $id)->update(['revoked_at' => now()]);
        $this->info("Token $id revoked.");

        return;
    }
    $plain = 'sw_'.Str::random(48);
    DB::table('api_tokens')->insert(['name' => $name, 'token_hash' => hash('sha256', $plain), 'ability' => 'ingest',
        'created_at' => now(), 'updated_at' => now()]);
    $this->info('Token (shown once, copy it now):');
    $this->line($plain);
})->purpose('Create (or revoke) an API token for the Android app');

Artisan::command('gadget:user {email} {--name=Owner}', function (string $email) {
    $pw = trim(fgets(STDIN));
    if (strlen($pw) < 10) {
        $this->error('Password (read from stdin) must have at least 10 characters.');

        return 1;
    }
    \App\Models\User::updateOrCreate(['email' => $email], ['name' => $this->option('name'), 'password' => $pw]);
    $this->info("User $email saved.");
})->purpose('Create or update the owner login (password from stdin)');

Schedule::command('gadget:cleanup')->dailyAt('03:30');
