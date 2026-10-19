<?php

namespace App\Support;

use Illuminate\Support\Facades\DB;

/** Deletes old data and old logs, using the day counts from the settings page. 0 = keep forever. */
class Cleanup
{
    public static function run(?int $dataDays = null, ?int $logDays = null): array
    {
        $dataDays ??= (int) Settings::get('retention_days_data');
        $logDays ??= (int) Settings::get('retention_days_logs');
        $r = ['samples' => 0, 'events' => 0, 'sessions' => 0, 'ingest_logs' => 0, 'log_files' => 0];

        if ($dataDays > 0) {
            $cut = time() - $dataDays * 86400;
            $r['samples'] = DB::table('samples')->where('ts', '<', $cut)->delete();
            $r['events'] = DB::table('events')->where('ts', '<', $cut * 1000)->delete();
            // a session goes when it ended (or started, if never closed) before the cutoff
            $r['sessions'] = DB::table('sleep_sessions')->whereRaw('coalesce(ended_at, started_at) < ?', [$cut])->delete();
        }
        if ($logDays > 0) {
            $r['ingest_logs'] = DB::table('ingest_logs')->where('created_at', '<', now()->subDays($logDays))->delete();
            foreach (glob(storage_path('logs').'/*.log') ?: [] as $f) {
                if (filemtime($f) < time() - $logDays * 86400 && @unlink($f)) {
                    $r['log_files']++;
                }
            }
        }

        return $r;
    }
}
