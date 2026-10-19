<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Validator;

class IngestController extends Controller
{
    public function ping(): JsonResponse
    {
        return response()->json(['ok' => true, 'server_time' => now()->toIso8601String()]);
    }

    public function ingest(Request $request): JsonResponse
    {
        $maxTs = time() + 86400;
        $minTs = 1420070400; // 2015-01-01
        $v = Validator::make($request->json()->all(), [
            'device' => 'required|array',
            'device.external_id' => 'required|string|max:255',
            'device.name' => 'nullable|string|max:255',
            'device.model' => 'nullable|string|max:255',
            'device.firmware' => 'nullable|string|max:255',
            'sessions' => 'sometimes|array|max:200',
            'sessions.*.uuid' => 'required|string|max:64',
            'sessions.*.started_at' => "required|integer|between:$minTs,$maxTs",
            'sessions.*.ended_at' => "nullable|integer|between:$minTs,$maxTs",
            'sessions.*.mode' => 'nullable|string|max:32',
            'sessions.*.target_time' => "nullable|integer|between:$minTs,$maxTs",
            'sessions.*.tolerance_min' => 'nullable|integer|between:0,600',
            'sessions.*.window_start' => "nullable|integer|between:$minTs,$maxTs",
            'sessions.*.deadline' => "nullable|integer|between:$minTs,$maxTs",
            'sessions.*.detector_id' => 'nullable|string|max:64',
            'sessions.*.detector_params' => 'nullable|array',
            'sessions.*.timezone' => 'nullable|string|max:64',
            'sessions.*.wake_reason' => 'nullable|string|max:32',
            'sessions.*.morning_rating' => 'nullable|integer|between:1,5',
            'sessions.*.notes' => 'nullable|string|max:5000',
            'samples' => 'sometimes|array|max:5000',
            'samples.*.ts' => "required|integer|between:$minTs,$maxTs",
            'samples.*.heart_rate' => 'nullable|integer|between:0,300',
            'samples.*.steps' => 'nullable|integer|min:0',
            'samples.*.intensity' => 'nullable|integer',
            'samples.*.movement' => 'nullable|integer',
            'samples.*.spo2' => 'nullable|integer|between:0,100',
            'samples.*.sleep_stage' => 'nullable|string|max:32',
            'samples.*.extra' => 'nullable|array',
            'events' => 'sometimes|array|max:5000',
            'events.*.session_uuid' => 'required|string|max:64',
            'events.*.ts' => 'required|integer|min:'.($minTs * 1000).'|max:'.($maxTs * 1000),
            'events.*.type' => 'required|string|max:48',
            'events.*.payload' => 'nullable|array',
        ]);
        if ($v->fails()) {
            $this->log($request, 422, [], 'validation: '.$v->errors()->first());

            return response()->json(['ok' => false, 'error' => 'validation', 'details' => $v->errors()], 422);
        }
        $d = $v->validated();

        try {
            $counts = DB::transaction(fn () => $this->store($d));
        } catch (\Throwable $e) {
            report($e);
            $this->log($request, 500, [], 'error: '.class_basename($e));

            return response()->json(['ok' => false, 'error' => 'server_error'], 500);
        }
        $this->log($request, 200, $counts);

        return response()->json([
            'ok' => true,
            'accepted' => ['sessions' => $counts['sessions'], 'samples' => $counts['samples'], 'events' => $counts['events']],
            'duplicates' => $counts['duplicates'],
        ]);
    }

    private function store(array $d): array
    {
        $now = now();
        $dev = $d['device'];
        DB::table('devices')->upsert([[
            'external_id' => $dev['external_id'], 'name' => $dev['name'] ?? null, 'model' => $dev['model'] ?? null,
            'firmware' => $dev['firmware'] ?? null, 'last_seen_at' => $now, 'created_at' => $now, 'updated_at' => $now,
        ]], ['external_id'], ['name', 'model', 'firmware', 'last_seen_at', 'updated_at']);
        $deviceId = DB::table('devices')->where('external_id', $dev['external_id'])->value('id');
        $dup = 0;

        // sessions: fields the client did not send keep their value (so notes edited on the web survive)
        $sessions = $d['sessions'] ?? [];
        $fields = ['ended_at', 'mode', 'target_time', 'tolerance_min', 'window_start', 'deadline', 'detector_id',
            'detector_params', 'timezone', 'wake_reason', 'morning_rating', 'notes'];
        foreach ($sessions as $s) {
            $row = DB::table('sleep_sessions')->where('uuid', $s['uuid'])->first();
            $data = ['device_id' => $deviceId, 'started_at' => $s['started_at'], 'updated_at' => $now];
            foreach ($fields as $f) {
                if (array_key_exists($f, $s) && $s[$f] !== null) {
                    $data[$f] = $f === 'detector_params' ? json_encode($s[$f]) : $s[$f];
                }
            }
            if ($row) {
                $dup++;
                DB::table('sleep_sessions')->where('id', $row->id)->update($data);
            } else {
                DB::table('sleep_sessions')->insert($data + ['uuid' => $s['uuid'], 'created_at' => $now]);
            }
        }

        // samples: upsert on (device_id, ts); a later batch overwrites fields it sends
        $samples = $d['samples'] ?? [];
        if ($samples) {
            $tsList = array_column($samples, 'ts');
            $existing = DB::table('samples')->where('device_id', $deviceId)
                ->whereBetween('ts', [min($tsList), max($tsList)])->pluck('ts')->flip();
            $rows = [];
            foreach ($samples as $s) {
                if (isset($existing[$s['ts']])) {
                    $dup++;
                }
                $rows[$s['ts']] = [
                    'device_id' => $deviceId, 'ts' => $s['ts'],
                    'heart_rate' => $s['heart_rate'] ?? null, 'steps' => $s['steps'] ?? null,
                    'intensity' => $s['intensity'] ?? null, 'movement' => $s['movement'] ?? null,
                    'spo2' => $s['spo2'] ?? null, 'sleep_stage' => $s['sleep_stage'] ?? null,
                    'extra' => ! empty($s['extra']) ? json_encode($s['extra']) : null,
                ];
            }
            foreach (array_chunk(array_values($rows), 500) as $chunk) {
                DB::table('samples')->upsert($chunk, ['device_id', 'ts'],
                    ['heart_rate', 'steps', 'intensity', 'movement', 'spo2', 'sleep_stage', 'extra']);
            }
        }

        // events: unique (session_id, ts, type)
        $events = $d['events'] ?? [];
        $acceptedEvents = 0;
        if ($events) {
            $map = DB::table('sleep_sessions')->whereIn('uuid', array_unique(array_column($events, 'session_uuid')))
                ->pluck('id', 'uuid');
            $rows = [];
            foreach ($events as $e) {
                $sid = $map[$e['session_uuid']] ?? null;
                if (! $sid) {
                    continue; // unknown session: the client must send the session first
                }
                $rows[$sid.'|'.$e['ts'].'|'.$e['type']] = [
                    'session_id' => $sid, 'device_id' => $deviceId, 'ts' => $e['ts'], 'type' => $e['type'],
                    'payload' => isset($e['payload']) ? json_encode($e['payload']) : null,
                ];
                if ($e['type'] === 'morning_rating' && isset($e['payload']['rating'])) {
                    $upd = ['morning_rating' => max(1, min(5, (int) $e['payload']['rating']))];
                    if (! empty($e['payload']['note'])) {
                        $upd['notes'] = mb_substr((string) $e['payload']['note'], 0, 5000);
                    }
                    DB::table('sleep_sessions')->where('id', $sid)->update($upd);
                }
                if ($e['type'] === 'wake_triggered' && isset($e['payload']['reason'])) {
                    DB::table('sleep_sessions')->where('id', $sid)->whereNull('wake_reason')
                        ->update(['wake_reason' => mb_substr((string) $e['payload']['reason'], 0, 32)]);
                }
            }
            $acceptedEvents = count($rows);
            foreach (array_chunk(array_values($rows), 500) as $chunk) {
                $before = DB::table('events')->whereIn('session_id', array_column($chunk, 'session_id'))
                    ->whereIn('ts', array_column($chunk, 'ts'))->count();
                DB::table('events')->upsert($chunk, ['session_id', 'ts', 'type'], ['payload']);
                $after = DB::table('events')->whereIn('session_id', array_column($chunk, 'session_id'))
                    ->whereIn('ts', array_column($chunk, 'ts'))->count();
                $dup += count($chunk) - ($after - $before);
            }
        }

        return ['sessions' => count($sessions), 'samples' => count($samples), 'events' => $acceptedEvents, 'duplicates' => $dup];
    }

    private function log(Request $request, int $status, array $c, ?string $message = null): void
    {
        DB::table('ingest_logs')->insert([
            'api_token_id' => $request->attributes->get('api_token_id'), 'ip' => $request->ip(), 'status' => $status,
            'sessions' => $c['sessions'] ?? 0, 'samples' => $c['samples'] ?? 0, 'events' => $c['events'] ?? 0,
            'duplicates' => $c['duplicates'] ?? 0, 'message' => $message ? mb_substr($message, 0, 500) : null,
        ]);
    }
}
