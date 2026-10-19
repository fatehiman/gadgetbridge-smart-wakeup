<?php

namespace App\Support;

use Illuminate\Support\Facades\DB;

/** Key/value settings stored in the app_settings table. */
class Settings
{
    public const DEFAULTS = [
        'timezone' => 'UTC',
        'retention_days_data' => '90',   // samples, events, sessions
        'retention_days_logs' => '90',   // ingest logs + laravel log files
    ];

    public static function get(string $key): string
    {
        $v = DB::table('app_settings')->where('key', $key)->value('value');

        return $v ?? self::DEFAULTS[$key] ?? '';
    }

    public static function set(string $key, string $value): void
    {
        DB::table('app_settings')->upsert([['key' => $key, 'value' => $value]], ['key'], ['value']);
    }

    public static function tz(): string
    {
        $tz = self::get('timezone');

        return in_array($tz, \DateTimeZone::listIdentifiers(), true) ? $tz : 'UTC';
    }
}
