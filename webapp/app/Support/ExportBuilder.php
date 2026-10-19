<?php

namespace App\Support;

use Illuminate\Support\Facades\DB;

/** Builds the export structure (sessions, samples, events) used by the web export and the analysis API. */
class ExportBuilder
{
    /** sessions + samples (each tagged with the session it falls in) + events. */
    public static function build($sessions, int $from, int $to): array
    {
        $devs = DB::table('devices')->pluck('external_id', 'id');
        $out = ['sessions' => [], 'samples' => [], 'events' => []];
        foreach ($sessions as $s) {
            $out['sessions'][] = [
                'uuid' => $s->uuid, 'device' => $devs[$s->device_id], 'started_at' => $s->started_at, 'ended_at' => $s->ended_at,
                'mode' => $s->mode, 'target_time' => $s->target_time, 'tolerance_min' => $s->tolerance_min,
                'window_start' => $s->window_start, 'deadline' => $s->deadline, 'detector_id' => $s->detector_id,
                'detector_params' => json_decode($s->detector_params ?? 'null'), 'timezone' => $s->timezone,
                'wake_reason' => $s->wake_reason, 'morning_rating' => $s->morning_rating, 'notes' => $s->notes,
            ];
        }
        $uuidFor = function ($dev, $ts) use ($sessions) {
            foreach ($sessions as $s) {
                if ($s->device_id == $dev && $ts >= $s->started_at && $ts <= ($s->ended_at ?? $s->started_at + 86400)) {
                    return $s->uuid;
                }
            }

            return null;
        };
        DB::table('samples')->whereBetween('ts', [$from, $to])->orderBy('ts')->chunk(5000, function ($rows) use (&$out, $uuidFor, $devs) {
            foreach ($rows as $x) {
                $out['samples'][] = [
                    'session_uuid' => $uuidFor($x->device_id, $x->ts), 'device' => $devs[$x->device_id], 'ts' => $x->ts,
                    'heart_rate' => $x->heart_rate, 'steps' => $x->steps, 'intensity' => $x->intensity, 'movement' => $x->movement,
                    'spo2' => $x->spo2, 'sleep_stage' => $x->sleep_stage, 'extra' => json_decode($x->extra ?? 'null'),
                ];
            }
        });
        $uuids = $sessions->pluck('uuid', 'id');
        foreach (DB::table('events')->whereIn('session_id', $sessions->pluck('id')->all())->orderBy('ts')->get() as $e) {
            $out['events'][] = [
                'session_uuid' => $uuids[$e->session_id], 'device' => $devs[$e->device_id], 'ts' => $e->ts,
                'type' => $e->type, 'payload' => json_decode($e->payload ?? 'null'),
            ];
        }

        return $out;
    }
}
