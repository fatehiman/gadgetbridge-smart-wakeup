@extends('layouts.app')
@section('title', 'Night '.\App\Support\Fmt::t($s->started_at, $tz, 'M j'))
@push('head')
    <script src="/vendor/chart.umd.js" defer></script>
    <script src="/assets/night.js?v={{ @filemtime(public_path('assets/night.js')) }}" defer></script>
@endpush
@section('content')
@php use App\Support\Fmt; @endphp
<div class="page-head">
    <h1 class="page-title"><svg class="ic"><use href="/assets/icons.svg#i-moon"/></svg> {{ Fmt::t($s->started_at, $tz, 'l, M j Y') }}</h1>
    <a class="btn" href="{{ route('nights') }}">← All nights</a>
</div>
<div class="grid4">
    <div class="stat c0"><b>{{ Fmt::dur($summary->duration) }}</b><span>Duration</span></div>
    <div class="stat c2"><b>{{ Fmt::t($summary->wake_ts, $tz, 'H:i') }}</b><span>Woke ({{ Fmt::reason($summary->wake_reason) }})</span></div>
    <div class="stat c1"><b>{{ $summary->avg_hr ?? '–' }} <small>bpm</small></b><span>Avg HR (min {{ $summary->min_hr ?? '–' }})</span></div>
    <div class="stat c3"><b>{{ Fmt::t($s->target_time, $tz, 'H:i') }} ±{{ $s->tolerance_min ?? '–' }}</b><span>Target · {{ str_replace('_', ' ', $s->mode ?? '–') }}</span></div>
</div>

<div class="card">
    <h2>Heart rate and movement</h2>
    <p class="small muted">Shaded band = wake window. Colored strip at the bottom = the watch's own sleep stages (dark blue deep, light blue light, purple REM, orange awake), which arrive only after the night. Lines: session start/stop, wake trigger, acknowledgement. Times in {{ $tz }}.</p>
    <div class="chart-box"><canvas id="chart-hr"></canvas></div>
    <div class="chart-box short" style="margin-top:14px"><canvas id="chart-mov"></canvas></div>
    <script type="application/json" id="night-data">@json($chart)</script>
</div>

<form class="card" method="post" action="{{ route('nights.update', $s->id) }}">
    @csrf
    <h2>Rating and notes</h2>
    <div class="stars">
        @foreach ([1, 2, 3, 4, 5] as $n)
            <label><input type="radio" name="morning_rating" value="{{ $n }}" @checked($s->morning_rating == $n)> {{ $n }} ★</label>
        @endforeach
    </div>
    <p><label for="notes">Notes</label><textarea id="notes" name="notes" rows="3">{{ old('notes', $s->notes) }}</textarea></p>
    <button class="btn primary">Save</button>
</form>

<div class="card table-wrap">
    <h2>Events ({{ $events->count() }})</h2>
    <table>
        <thead><tr><th>Time</th><th>Type</th><th>Details</th></tr></thead>
        <tbody>
        @foreach ($events as $e)
            <tr>
                <td>{{ Fmt::t(intdiv($e->ts, 1000), $tz, 'H:i:s') }}</td>
                <td><span class="pill c{{ $e->type === 'wake_triggered' ? 1 : ($e->type === 'detector_evaluated' ? 0 : 3) }}">{{ $e->type }}</span></td>
                <td>@if ($e->payload)<details><summary>payload</summary><pre>{{ json_encode(json_decode($e->payload), JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES) }}</pre></details>@endif</td>
            </tr>
        @endforeach
        </tbody>
    </table>
</div>
@endsection
