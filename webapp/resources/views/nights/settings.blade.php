@extends('layouts.app')
@section('title', 'Settings')
@section('content')
@php use App\Support\Fmt; @endphp
<div class="page-head">
    <h1 class="page-title"><svg class="ic"><use href="/assets/icons.svg#i-settings"/></svg> Settings</h1>
</div>

<form class="card" method="post" action="{{ route('settings.save') }}">
    @csrf
    <h2>General and cleanup</h2>
    <div class="grid2">
        <div><label for="timezone">Time zone (for display)</label>
            <select id="timezone" name="timezone">@foreach ($zones as $z)<option @selected($z === $tz)>{{ $z }}</option>@endforeach</select>
            @error('timezone')<div class="errors">{{ $message }}</div>@enderror</div>
        <div></div>
        <div><label for="d1">Delete data older than (days)</label>
            <input id="d1" type="number" name="retention_days_data" min="0" max="36500" value="{{ old('retention_days_data', $dataDays) }}">
            <div class="small muted">Samples, events and nights. Default 90. 0 = keep forever.</div>
            @error('retention_days_data')<div class="errors">{{ $message }}</div>@enderror</div>
        <div><label for="d2">Delete logs older than (days)</label>
            <input id="d2" type="number" name="retention_days_logs" min="0" max="36500" value="{{ old('retention_days_logs', $logDays) }}">
            <div class="small muted">Ingest log and server log files. Default 90. 0 = keep forever.</div>
            @error('retention_days_logs')<div class="errors">{{ $message }}</div>@enderror</div>
    </div>
    <p class="small muted">The cleanup runs every day at 03:30 (server time). Stored now: {{ number_format($counts['samples']) }} samples, {{ number_format($counts['events']) }} events, {{ $counts['sessions'] }} nights, {{ number_format($counts['logs']) }} ingest logs.</p>
    <button class="btn primary">Save</button>
</form>
<form class="card" method="post" action="{{ route('settings.cleanup') }}" onsubmit="return confirm('Delete old data now, using the saved day counts? This cannot be undone.')">
    @csrf
    <h2>Run cleanup now</h2>
    <p class="small muted">Uses the saved values above. Save first if you changed them.</p>
    <button class="btn danger"><svg class="ic"><use href="/assets/icons.svg#i-trash-2"/></svg> Clean up now</button>
</form>

<div class="card">
    <h2>API tokens (for the Android app)</h2>
    @if ($newToken)
        <p>Copy this token now. It is shown only once:</p>
        <div class="token-box">{{ $newToken }}</div>
    @endif
    <form class="row" method="post" action="{{ route('tokens.create') }}" style="margin:12px 0">
        @csrf
        <div><label for="tn">Name</label><input id="tn" name="name" placeholder="e.g. my phone" required></div>
        <div class="auto"><button class="btn primary"><svg class="ic"><use href="/assets/icons.svg#i-plus"/></svg> Create token</button></div>
    </form>
    <div class="table-wrap"><table>
        <thead><tr><th>Name</th><th>Created</th><th>Last used</th><th>Status</th><th></th></tr></thead>
        <tbody>
        @forelse ($tokens as $t)
            <tr>
                <td>{{ $t->name }}</td><td>{{ Fmt::t(strtotime($t->created_at), $tz) }}</td>
                <td>{{ $t->last_used_at ? Fmt::t(strtotime($t->last_used_at), $tz) : 'never' }}</td>
                <td>{!! $t->revoked_at ? '<span class="pill c5">revoked</span>' : '<span class="pill c2">active</span>' !!}</td>
                <td>@unless ($t->revoked_at)
                    <form method="post" action="{{ route('tokens.revoke', $t->id) }}" onsubmit="return confirm('Revoke this token?')">@csrf
                        <button class="icon-btn danger" title="Revoke" aria-label="Revoke"><svg class="ic"><use href="/assets/icons.svg#i-trash-2"/></svg></button></form>
                @endunless</td>
            </tr>
        @empty
            <tr><td colspan="5" class="muted">No tokens yet.</td></tr>
        @endforelse
        </tbody>
    </table></div>
</div>

<div class="card table-wrap">
    <h2>Devices and last sync</h2>
    <table>
        <thead><tr><th>Device</th><th>Model</th><th>Firmware</th><th>Last sync</th></tr></thead>
        <tbody>
        @forelse ($devices as $d)
            <tr><td>{{ $d->name ?? $d->external_id }}</td><td>{{ $d->model ?? '–' }}</td><td>{{ $d->firmware ?? '–' }}</td>
                <td>{{ $d->last_seen_at ? Fmt::t(strtotime($d->last_seen_at), $tz, 'Y-m-d H:i:s') : '–' }}</td></tr>
        @empty
            <tr><td colspan="4" class="muted">No device has synced yet.</td></tr>
        @endforelse
        </tbody>
    </table>
</div>

<div class="card table-wrap">
    <h2>Latest ingest requests</h2>
    <table>
        <thead><tr><th>Time</th><th>Status</th><th>Sessions</th><th>Samples</th><th>Events</th><th>Duplicates</th><th>Message</th></tr></thead>
        <tbody>
        @forelse ($logs as $l)
            <tr><td>{{ Fmt::t(strtotime($l->created_at), $tz, 'Y-m-d H:i:s') }}</td>
                <td><span class="pill c{{ $l->status === 200 ? 2 : 5 }}">{{ $l->status }}</span></td>
                <td>{{ $l->sessions }}</td><td>{{ $l->samples }}</td><td>{{ $l->events }}</td><td>{{ $l->duplicates }}</td><td class="small">{{ $l->message }}</td></tr>
        @empty
            <tr><td colspan="7" class="muted">Nothing yet.</td></tr>
        @endforelse
        </tbody>
    </table>
</div>
@endsection
