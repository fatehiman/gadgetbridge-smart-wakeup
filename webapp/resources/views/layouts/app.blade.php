@php
    $icons = '/assets/icons.svg';
    $v = fn ($f) => '/assets/'.$f.'?v='.@filemtime(public_path('assets/'.$f));
@endphp
<!doctype html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
    <meta name="robots" content="noindex, nofollow">
    <meta name="theme-color" content="#eef5ff">
    <title>@yield('title', 'Gadget') · Gadget</title>
    <link rel="icon" href="/assets/favicon.svg" type="image/svg+xml">
    <link rel="stylesheet" href="{{ $v('app.css') }}">
    @stack('head')
</head>
<body>
@auth
    <header class="topbar">
        <a class="brand" href="{{ route('nights') }}">
            <span class="brand-logo"><svg class="ic"><use href="{{ $icons }}#i-moon"/></svg></span>
            <span>Gadget</span>
        </a>
        <nav class="mainnav">
            @foreach ([['nights', 'moon', 'Nights', 'nights*'], ['trends', 'trending-up', 'Trends', 'trends'], ['export', 'download', 'Export', 'export*'], ['settings', 'settings', 'Settings', 'settings*']] as [$route, $icon, $label, $pattern])
                <a href="{{ route($route) }}" class="navlink c{{ $loop->index }} {{ request()->routeIs($pattern) ? 'active' : '' }}" title="{{ $label }}">
                    <svg class="ic"><use href="{{ $icons }}#i-{{ $icon }}"/></svg><span>{{ $label }}</span>
                </a>
            @endforeach
        </nav>
        <div class="usermenu">
            <form method="post" action="{{ route('logout') }}">
                @csrf
                <button class="icon-btn" type="submit" title="Log out" aria-label="Log out"><svg class="ic"><use href="{{ $icons }}#i-log-out"/></svg></button>
            </form>
        </div>
    </header>
@endauth
<main class="page">
    @if (session('ok'))<div class="flash ok">{{ session('ok') }}</div>@endif
    @if (session('err'))<div class="flash err">{{ session('err') }}</div>@endif
    @yield('content')
</main>
</body>
</html>
