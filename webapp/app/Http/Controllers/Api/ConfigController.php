<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use Illuminate\Http\JsonResponse;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

/**
 * Detector config. The phone downloads the newest one (GET /config), the analysis side writes a new one (PUT /config).
 * Only the detector and its numbers can be changed here. The wake window and the deadline guarantee live in the app.
 */
class ConfigController extends Controller
{
    public const DETECTORS = ['heuristic_v1', 'scored_v1'];

    public function show(): JsonResponse
    {
        $row = DB::table('detector_configs')->orderByDesc('id')->first();

        return response()->json([
            'ok' => true,
            'version' => $row?->id ?? 0,
            'config' => $row ? json_decode($row->config, true) : null,
            'note' => $row?->note,
            'created_at' => $row?->created_at,
        ]);
    }

    public function history(): JsonResponse
    {
        $rows = DB::table('detector_configs')->orderByDesc('id')->limit(50)->get()->map(fn ($r) => [
            'version' => $r->id, 'created_at' => $r->created_at, 'note' => $r->note, 'config' => json_decode($r->config, true),
        ]);

        return response()->json(['ok' => true, 'history' => $rows]);
    }

    public function store(Request $request): JsonResponse
    {
        $err = self::validateConfig($request->json('config'));
        if ($err) {
            return response()->json(['ok' => false, 'error' => 'validation', 'details' => $err], 422);
        }
        $id = DB::table('detector_configs')->insertGetId([
            'note' => mb_substr((string) $request->json('note', ''), 0, 1000) ?: null,
            'config' => json_encode($request->json('config')),
        ]);

        return response()->json(['ok' => true, 'version' => $id]);
    }

    /** Returns an error text, or null when the config is acceptable. */
    public static function validateConfig(mixed $c): ?string
    {
        if (! is_array($c) || ! isset($c['detector']) || ! in_array($c['detector'], self::DETECTORS, true)) {
            return 'config.detector must be one of: '.implode(', ', self::DETECTORS);
        }
        if (strlen(json_encode($c)) > 20000) {
            return 'config too large';
        }
        $params = $c['params'] ?? [];
        if (! is_array($params)) {
            return 'config.params must be an object';
        }
        $bad = false;
        array_walk_recursive($params, function ($v) use (&$bad) {
            if (! is_int($v) && ! is_float($v) && ! is_string($v) && ! is_bool($v) && $v !== null) {
                $bad = true;
            }
        });

        return $bad ? 'config.params contains an invalid value' : null;
    }
}
