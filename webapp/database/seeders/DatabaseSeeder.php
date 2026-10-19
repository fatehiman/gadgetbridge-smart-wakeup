<?php

namespace Database\Seeders;

use Illuminate\Database\Seeder;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Str;

/** Fake nights for development: `php artisan db:seed` (never run this on production). */
class DatabaseSeeder extends Seeder
{
    public function run(): void
    {
        $now = now();
        DB::table('devices')->upsert([[
            'external_id' => 'demo-device', 'name' => 'CMF Watch Pro 2 (demo)', 'model' => '3BAD', 'firmware' => '1.0.0',
            'last_seen_at' => $now, 'created_at' => $now, 'updated_at' => $now,
        ]], ['external_id'], ['name']);
        $dev = DB::table('devices')->where('external_id', 'demo-device')->value('id');

        for ($n = 14; $n >= 1; $n--) {
            $day = now('Europe/Helsinki')->subDays($n)->setTime(23, 0);
            $start = $day->timestamp + random_int(0, 1800);
            $target = $day->addDay()->setTime(8, 0)->timestamp;
            $smart = $n % 2 === 0;
            $wake = $smart ? $target - random_int(0, 900) : $target;
            $reason = $smart ? (random_int(0, 2) ? 'LIGHT_SLEEP' : 'DEADLINE') : 'MONITOR_TARGET';
            $uuid = (string) Str::uuid();
            $sid = DB::table('sleep_sessions')->insertGetId([
                'uuid' => $uuid, 'device_id' => $dev, 'started_at' => $start, 'ended_at' => $wake + 120,
                'mode' => $smart ? 'smart_wake' : 'monitor_only', 'target_time' => $target, 'tolerance_min' => 15,
                'window_start' => $target - 900, 'deadline' => $target + 900, 'detector_id' => $smart ? 'heuristic_v1' : 'noop',
                'detector_params' => json_encode(['hr_delta' => 8]), 'timezone' => 'Europe/Helsinki', 'wake_reason' => $reason,
                'morning_rating' => random_int(2, 5), 'created_at' => $now, 'updated_at' => $now,
            ]);
            $rows = [];
            for ($t = $start; $t <= $wake + 120; $t += 60) {
                $phase = ($t - $start) / max(1, $wake - $start);
                $base = 52 + 8 * sin($phase * M_PI * 6) + 10 * $phase;
                $rows[] = [
                    'device_id' => $dev, 'ts' => $t, 'heart_rate' => (int) round($base + random_int(-3, 3)), 'steps' => 0,
                    'intensity' => random_int(0, 9) > 7 ? random_int(1, 40) : 0, 'movement' => random_int(0, 9) > 8 ? random_int(1, 20) : 0,
                    'spo2' => random_int(94, 99), 'sleep_stage' => null, 'extra' => null,
                ];
            }
            foreach (array_chunk($rows, 500) as $chunk) {
                DB::table('samples')->insert($chunk);
            }
            $ev = [['session_started', $start], ['wake_triggered', $wake], ['wake_acknowledged', $wake + 40], ['session_stopped', $wake + 120]];
            foreach ($ev as [$type, $ts]) {
                DB::table('events')->insert(['session_id' => $sid, 'device_id' => $dev, 'ts' => $ts * 1000, 'type' => $type,
                    'payload' => $type === 'wake_triggered' ? json_encode(['reason' => $reason]) : null]);
            }
            for ($t = $target - 900; $smart && $t <= $wake; $t += 300) {
                DB::table('events')->insert(['session_id' => $sid, 'device_id' => $dev, 'ts' => $t * 1000, 'type' => 'detector_evaluated',
                    'payload' => json_encode(['decision' => $t >= $wake - 60 && $reason === 'LIGHT_SLEEP' ? 'wake' : 'wait', 'hr_recent' => 55, 'mov_recent' => 0])]);
            }
        }
    }
}
