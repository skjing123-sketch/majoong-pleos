package TrialCore;
use strict; use warnings;
use Exporter 'import';
use POSIX qw(isfinite);
use TrialConfig;
our @EXPORT_OK = qw(identity_ok gate_reason parse_speed fresh_sample advance point nmea_pair safe_output lease_reason accept_sample parse_lease record_identity_ok speed_sample_for_mode);
our $BASE=$TrialConfig::BASE;
our $DISTANCE=77.29; # replaced below by the exact same haversine public test endpoints
our @START=@TrialConfig::START; our @END=@TrialConfig::END;
my $pi=4*atan2(1,1); my ($a,$b,$c,$d)=map {$_*$pi/180} (@START,@END);
my $h=sin(($c-$a)/2)**2+cos($a)*cos($c)*sin(($d-$b)/2)**2;
$DISTANCE=6371000*2*atan2(sqrt($h),sqrt(1-$h));
our $BEARING=(atan2(sin($d-$b)*cos($c),cos($a)*sin($c)-sin($a)*cos($c)*cos($d-$b))*180/$pi+360)%360;
sub identity_ok {
 my ($got,$want)=@_; for my $x($got,$want){return 0 unless ref($x) eq 'HASH';for my $k(qw(pid uid)){return 0 unless defined($x->{$k})&&$x->{$k}=~/\A\d+\z/}return 0 unless defined($x->{start})} return 0 unless defined($got->{command}); return 0 unless $got->{pid}==$want->{pid} && $got->{uid}==$want->{uid};
 return 0 unless $got->{start} eq $want->{start};
 my @a=split /\s+/, $got->{command};
 return 0 unless @a && ($a[0] eq "$BASE/sdk/emulator/qemu/darwin-aarch64/qemu-system-aarch64-headless" || $a[0] eq "$BASE/sdk/emulator/qemu/darwin-aarch64/qemu-system-aarch64");
 my ($avd,$port,$na,$np); $na=$np=0;
 for(my $i=1;$i<@a;$i++){ if($a[$i] eq '-avd'){$avd=$a[$i+1];$na++} if($a[$i] eq '-port'){$port=$a[$i+1];$np++} }
 return $na==1 && $np==1 && defined($avd) && $avd eq $TrialConfig::AVD && defined($port) && $port eq $TrialConfig::EMULATOR_PORT;
}
sub gate_reason {
 my (%x)=@_; return 'identity_mismatch' unless $x{identity};
 return 'external_mount_mismatch' unless $x{mount};
 return 'resource_unknown' unless defined($x{pressure}) && $x{pressure}=~/\A[124]\z/ && defined($x{internal}) && $x{internal}=~/\A\d+\z/ && defined($x{external}) && $x{external}=~/\A\d+\z/ && defined($x{battery}) && $x{battery}=~/\A\d+\z/ && $x{battery}<=100;
 return 'target_pressure_warning' unless $x{pressure}==1;
 return 'battery_low' if $x{battery}<30;
 return 'internal_disk_low' if $x{internal}<8388608;
 return 'external_disk_low' if $x{external}<1048576;
 return 'normal';
}
sub safe_output { my($p)=@_; return defined($p) && $p =~ m{\A\Q$BASE\E/verification/galaxy-only-[A-Za-z0-9_-]+\z}; }
sub parse_speed {
 my($line)=@_;return {error=>'sdk_vehicle_sample_ended'} if $line=~/\bMajoongAudit\b/&&$line=~/\bevent=vehicle_sample_end\b/;return undef unless $line=~/\bMajoongAudit\b/&&$line=~/\bevent=vehicle_sample\b/;
 return {error=>'invalid_sdk_vehicle_sample'} unless $line=~/^\s*(\d+\.\d+).*?event=vehicle_sample\s+seq=(\d+)\s+elapsedMs=(\d+)\s+speedMps=([0-9]+(?:\.[0-9]+)?)\s+parked=(true|false)\s+fresh=(true|false)(?:\s|$)/;
 my($epoch,$seq,$elapsed,$mps,$park,$fresh)=(0+$1,0+$2,0+$3,0+$4,$5,$6);
 return {error=>'invalid_sdk_vehicle_sample'} unless isfinite($epoch)&&isfinite($mps)&&$mps>=0&&$mps<=250/3.6;
 return {error=>'unfresh_sdk_vehicle_sample'} unless $fresh eq 'true';
 return {epoch=>$epoch,seq=>$seq,elapsed=>$elapsed,speed=>$mps*3.6,parked=>$park eq 'true'?1:0};
}
sub speed_sample_for_mode {
 my($mode,$line)=@_;die "invalid_speed_mode\n" unless $mode=~/\A(?:static|observe|drive)\z/;
 my $parsed=parse_speed($line);return undef unless $parsed;
 if($parsed->{error}){die "$parsed->{error}\n" if $mode eq 'drive';return {%$parsed,metadata_only=>1}}
 return $parsed;
}
sub parse_lease {
 my($line,$now,$previous)=@_;return undef unless $line=~/\ALEASE ([0-9]+) ([0-9]+)\z/;
 my($epoch,$seq)=(0+$1,0+$2);return undef unless isfinite($now)&&$now-$epoch>=-2&&$now-$epoch<=3;
 return undef if defined($previous)&&$seq<=$previous;return {epoch=>$epoch,seq=>$seq};
}
sub record_identity_ok {
 my($raw,$expected)=@_;my@args=split /\0/,$raw;return 0 unless @args&&($args[0] eq 'screenrecord'||$args[0] eq '/system/bin/screenrecord');$args[0]='screenrecord';
 return join(' ',@args) eq $expected;
}
sub lease_reason {my($last,$now,$eof)=@_;return 'control_connection_lost' if $eof;return 'control_lease_stale' unless defined($last)&&isfinite($last)&&isfinite($now)&&$now-$last>=0&&$now-$last<=3;return undef}
sub accept_sample {my($old,$new,$epoch)=@_;return 0 unless $new&&isfinite($epoch)&&$epoch-$new->{epoch}>=-2&&$epoch-$new->{epoch}<=3;return 0 if $old&&$new->{epoch}<=$old->{epoch};return 0 if $old&&defined($old->{elapsed})&&defined($new->{elapsed})&&$new->{elapsed}<=$old->{elapsed};return 1}
sub fresh_sample {
 my($s,$epoch,$mono)=@_; return 'no_vehicle_speed_observed' unless $s && $s->{seen};
 for my $key(qw(epoch received speed)){return 'invalid_vehicle_speed' unless defined($s->{$key})&&isfinite($s->{$key})}
 return 'stale_measurement' if defined($s->{measured_host})&&(!isfinite($s->{measured_host})||$mono-$s->{measured_host} < -1||$mono-$s->{measured_host}>3);
 return 'invalid_vehicle_speed' unless isfinite($epoch)&&isfinite($mono)&&$s->{speed}>=0&&$s->{speed}<=250;
 return 'stale_vehicle_speed' unless $epoch-$s->{epoch}>=-2 && $epoch-$s->{epoch}<=3 && $mono-$s->{received}>=0 && $mono-$s->{received}<=3;
 return undef;
}
sub advance {
 my($metres,$previous,$s,$last,$now,$drive,$epoch)=@_;
 return ($metres,undef,0) if $now<$drive;
 my $error=fresh_sample($s,$epoch,$now); die "$error\n" if $error;
 my $from=$last>$drive?$last:$drive;
 $from=$s->{received} if !defined($previous)&&$s->{received}>$from;
 my $dt=$now>$from?$now-$from:0;
 my $average=defined($previous)?($previous+$s->{speed})/2:$s->{speed};
 $metres+=$average/3.6*$dt; $metres=0 if $metres<0; $metres=$DISTANCE if $metres>$DISTANCE;
 return ($metres,$s->{speed},$s->{speed});
}
sub point { my($m)=@_; my $f=$m/$DISTANCE;$f=0 if $f<0;$f=1 if $f>1; return ($START[0]+($END[0]-$START[0])*$f,$START[1]+($END[1]-$START[1])*$f); }
sub coord {my($x,$lat)=@_;my $n=abs($x);my $deg=int($n);return sprintf($lat?'%02d%07.4f':'%03d%07.4f',$deg,($n-$deg)*60)}
sub sentence {my($body)=@_;my $v=0;$v^=ord($_) for split //,$body;return sprintf('$%s*%02X',$body,$v)}
sub nmea_pair {
 my($lat,$lon,$epoch,$kph,$bearing)=@_;my @t=gmtime($epoch);my $clock=sprintf('%02d%02d%02d',$t[2],$t[1],$t[0]);my $date=sprintf('%02d%02d%02d',$t[3],$t[4]+1,($t[5]+1900)%100);
 my($la,$lo)=(coord($lat,1),coord($lon,0));my($ns,$ew)=($lat>=0?'N':'S',$lon>=0?'E':'W');
 return (sentence("GPGGA,$clock,$la,$ns,$lo,$ew,1,08,0.9,20.0,M,0.0,M,,"),sentence(sprintf('GPRMC,%s,A,%s,%s,%s,%s,%.3f,%.1f,%s,0.0,E',$clock,$la,$ns,$lo,$ew,$kph/1.852,$bearing,$date)));
}
1;
