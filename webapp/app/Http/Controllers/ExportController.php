<?php

namespace App\Http\Controllers;

use App\Support\Settings;
use Carbon\CarbonImmutable;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

class ExportController extends Controller
{
    private const COLS = [
        'sessions' => ['uuid', 'device', 'started_at', 'ended_at', 'mode', 'target_time', 'tolerance_min', 'window_start', 'deadline',
            'detector_id', 'detector_params', 'timezone', 'wake_reason', 'morning_rating', 'notes'],
        'samples' => ['session_uuid', 'device', 'ts', 'heart_rate', 'steps', 'intensity', 'movement', 'spo2', 'sleep_stage', 'extra'],
        'events' => ['session_uuid', 'device', 'ts', 'type', 'payload'],
    ];

    public function index()
    {
        return view('nights.export', ['cols' => self::COLS, 'tz' => Settings::tz()]);
    }

    public function download(Request $r)
    {
        $c = $r->validate([
            'from' => 'required|date', 'to' => 'required|date|after_or_equal:from',
            'format' => 'required|in:csv,json', 'split' => 'required|in:combined,per_night',
        ]);
        $tz = Settings::tz();
        $from = CarbonImmutable::parse($c['from'], $tz)->startOfDay()->timestamp;
        $to = CarbonImmutable::parse($c['to'], $tz)->endOfDay()->timestamp;
        $sessions = DB::table('sleep_sessions')->join('devices', 'devices.id', '=', 'sleep_sessions.device_id')
            ->whereBetween('started_at', [$from, $to])->orderBy('started_at')
            ->get(['sleep_sessions.*', 'devices.external_id as device']);
        $name = "gadget-{$c['from']}_{$c['to']}";

        if ($c['split'] === 'combined') {
            $parts = $this->build($sessions, $from, $to);
            if ($c['format'] === 'json') {
                return response(json_encode($parts, JSON_UNESCAPED_SLASHES), 200, [
                    'Content-Type' => 'application/json',
                    'Content-Disposition' => "attachment; filename=\"$name.json\"",
                ]);
            }

            return $this->zip($name, $this->csvFiles($parts, ''));
        }

        // one file set per night: samples between started_at and ended_at
        $files = [];
        foreach ($sessions as $s) {
            $label = CarbonImmutable::createFromTimestamp($s->started_at, $s->timezone ?: $tz)->format('Y-m-d_Hi').'_'.substr($s->uuid, 0, 8);
            $parts = $this->build(collect([$s]), $s->started_at, $s->ended_at ?? $s->started_at + 86400);
            if ($c['format'] === 'json') {
                $files["$label.json"] = json_encode($parts, JSON_UNESCAPED_SLASHES);
            } else {
                $files += $this->csvFiles($parts, "$label-");
            }
        }

        return $this->zip($name, $files);
    }

    /** sessions + samples (each tagged with the session it falls in) + events. */
    private function build($sessions, int $from, int $to): array
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

    private function csvFiles(array $parts, string $prefix): array
    {
        $files = [];
        foreach (self::COLS as $kind => $cols) {
            $h = fopen('php://temp', 'w+');
            fputcsv($h, $cols);
            foreach ($parts[$kind] as $row) {
                fputcsv($h, array_map(fn ($c) => is_array($row[$c]) || is_object($row[$c]) ? json_encode($row[$c]) : $row[$c], $cols));
            }
            rewind($h);
            $files["$prefix$kind.csv"] = stream_get_contents($h);
            fclose($h);
        }

        return $files;
    }

    private function zip(string $name, array $files)
    {
        $tmp = tempnam(sys_get_temp_dir(), 'wz');
        $z = new \ZipArchive;
        $z->open($tmp, \ZipArchive::OVERWRITE);
        foreach ($files as $n => $content) {
            $z->addFromString($n, $content);
        }
        $z->close();

        return response()->download($tmp, "$name.zip")->deleteFileAfterSend(true);
    }
}
