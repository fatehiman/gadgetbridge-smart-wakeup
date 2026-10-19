<?php

namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;

/**
 * Bearer token auth. Tokens are stored as sha256 hashes and can be revoked.
 * Abilities: `ingest` (the phone) and `analyze` (read all data, write the detector config).
 * Usage: `api.token:ingest,analyze` = either ability is enough.
 */
class ApiToken
{
    public function handle(Request $request, Closure $next, string ...$abilities)
    {
        $abilities = $abilities ?: ['ingest'];
        $plain = $request->bearerToken();
        $row = $plain ? DB::table('api_tokens')->where('token_hash', hash('sha256', $plain))
            ->whereNull('revoked_at')->whereIn('ability', $abilities)->first() : null;
        if (! $row) {
            return response()->json(['ok' => false, 'error' => 'unauthorized'], 401);
        }
        DB::table('api_tokens')->where('id', $row->id)->update(['last_used_at' => now()]);
        $request->attributes->set('api_token_id', $row->id);
        $request->attributes->set('api_token_ability', $row->ability);

        return $next($request);
    }
}
