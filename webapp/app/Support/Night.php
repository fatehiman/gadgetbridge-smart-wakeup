<?php

namespace App\Support;

use Illuminate\Support\Facades\DB;

/** Read-side helpers: per-night summaries and chart data. Samples are matched to sessions at read time. */
class Night
{
    /** Summary rows (with HR stats) for a list of session rows. */
    public static function summaries($sessions): array
    {
        $out = [];
        foreach ($sessions as $s) {
            $end = $s->ended_at ?? $s->started_at;
            $hr = DB::table('samples')->where('device_id', $s->device_id)
                ->whereBetween('ts', [$s->started_at, $end])->whereNotNull('heart_rate')
                ->selectRaw('avg(heart_rate) as a, min(heart_rate) as mn, count(*) as c')->first();
            $wake = DB::table('events')->where('session_id', $s->id)->where('type', 'wake_triggered')->orderBy('ts')->first();
            $reason = $s->wake_reason;
            if (! $reason && $wake) {
                $reason = (json_decode($wake->payload ?? 'null', true) ?: [])['reason'] ?? null;
            }
            $out[] = (object) [
                's' => $s,
                'avg_hr' => $hr->c ? (int) round($hr->a) : null,
                'min_hr' => $hr->c ? $hr->mn : null,
                'wake_ts' => $wake ? intdiv($wake->ts, 1000) : $s->ended_at,
                'wake_reason' => $reason,
                'duration' => $s->ended_at ? $s->ended_at - $s->started_at : null,
            ];
        }

        return $out;
    }

    /** Samples of a night, downsampled to at most $max points (bucket average). */
    public static function chartSamples(object $s, int $max = 1500): array
    {
        $end = $s->ended_at ?? ($s->started_at + 86400);
        $rows = DB::table('samples')->where('device_id', $s->device_id)->whereBetween('ts', [$s->started_at, $end])
            ->orderBy('ts')->get(['ts', 'heart_rate', 'movement', 'intensity', 'spo2', 'sleep_stage', 'extra']);
        $step = max(1, (int) ceil($rows->count() / $max));
        $pts = [];
        foreach ($rows->chunk($step) as $chunk) {
            $avg = function (string $f) use ($chunk) {
                $v = $chunk->pluck($f)->filter(fn ($x) => $x !== null);

                return $v->isEmpty() ? null : round($v->avg(), 1);
            };
            $pm = $chunk->map(fn ($r) => $r->extra ? (json_decode($r->extra, true)['pm'] ?? null) : null)->filter(fn ($x) => $x !== null);
            $pts[] = [
                'pm' => $pm->isEmpty() ? null : round($pm->avg(), 2),
                'ts' => $chunk->first()->ts,
                'hr' => $avg('heart_rate'),
                'mov' => $avg('movement') ?? $avg('intensity'),
                'spo2' => $avg('spo2'),
                'stage' => $chunk->pluck('sleep_stage')->filter()->first(),
            ];
        }

        return $pts;
    }
}
