<?php

namespace App\Support;

use Carbon\CarbonImmutable;

/** Small formatting helpers for the Blade views. */
class Fmt
{
    public static function t(?int $ts, string $tz, string $format = 'Y-m-d H:i'): string
    {
        return $ts ? CarbonImmutable::createFromTimestamp($ts, $tz)->format($format) : '–';
    }

    public static function dur(?int $sec): string
    {
        return $sec === null ? '–' : intdiv($sec, 3600).'h '.str_pad((string) intdiv($sec % 3600, 60), 2, '0', STR_PAD_LEFT).'m';
    }

    public static function reason(?string $r): string
    {
        return $r ? ucwords(strtolower(str_replace('_', ' ', $r))) : '–';
    }
}
