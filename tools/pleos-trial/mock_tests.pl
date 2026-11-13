#!/usr/bin/perl
use strict;use warnings;use FindBin;use lib $FindBin::Bin;
BEGIN {
 # Synthetic fixtures only: no real host, mount, account, or observed location.
 $ENV{MAJOONG_TRIAL_MOUNT}='/Volumes/TrialDisk';
 $ENV{MAJOONG_TRIAL_BASE}='/Volumes/TrialDisk/MajoongTrial';
 $ENV{MAJOONG_TARGET_USER}='trial-user';
 $ENV{MAJOONG_AVD_NAME}='MajoongTrial';
 $ENV{MAJOONG_ADB_PORT}=15037;$ENV{MAJOONG_EMULATOR_PORT}=5568;
 $ENV{MAJOONG_ROUTE_START_LAT}='48.1173';$ENV{MAJOONG_ROUTE_START_LON}='11.5167';
 $ENV{MAJOONG_ROUTE_END_LAT}='48.1173';$ENV{MAJOONG_ROUTE_END_LON}='11.5178';
}
use Test::More;use TrialCore qw(identity_ok gate_reason parse_speed fresh_sample advance point nmea_pair safe_output lease_reason accept_sample parse_lease record_identity_ok);
use TrialTransport qw(bounded_command stop_owned);
use Time::HiRes qw(time);
my $base=$TrialCore::BASE;my $cmd="$base/sdk/emulator/qemu/darwin-aarch64/qemu-system-aarch64-headless -avd MajoongTrial -port 5568 -no-window";
my $want={pid=>2345,uid=>501,start=>'Thu Nov 12 18:00:00 2026'};my $got={%$want,command=>$cmd};
ok(identity_ok($got,$want),'new owned QEMU identity accepted');
for my $x(['pid',2346],['uid',502],['start','Thu Nov 12 18:00:01 2026'],['command','/usr/bin/sleep 10'],['command',"$cmd -avd Other"],['command',"$cmd -port 5569"],['command',"$base/sdk/emulator/qemu/other/qemu-system-aarch64 -avd MajoongTrial -port 5568"],['uid','unknown']){my %bad=(%$got,$x->[0]=>$x->[1]);ok(!identity_ok(\%bad,$want),"identity rejects $x->[0]=$x->[1]")}
my %normal=(identity=>1,mount=>1,pressure=>1,internal=>8388608,external=>1048576,battery=>88);
is(gate_reason(%normal),'normal','exact documented target floors accepted');
for my $x(['identity',0,'identity_mismatch'],['mount',0,'external_mount_mismatch'],['pressure',2,'target_pressure_warning'],['pressure',4,'target_pressure_warning'],['pressure','bad','resource_unknown'],['internal',8388607,'internal_disk_low'],['external',1048575,'external_disk_low'],['internal',undef,'resource_unknown'],['battery',29,'battery_low'],['battery',undef,'resource_unknown'],['battery',101,'resource_unknown']){is(gate_reason(%normal,$x->[0]=>$x->[1]),$x->[2],"gate $x->[0] boundary fails closed")}
ok(safe_output("$base/verification/galaxy-only-20261112_1800"),'strict external output accepted');
for my $bad('/tmp/out',"$base/verification/galaxy-only-../out","$base/verification/old","$base/verification/galaxy-only-a/child"){ok(!safe_output($bad),'unsafe or arbitrary output rejected')}
my $line='1794481200.250 I/MajoongAudit: event=vehicle_sample seq=0 elapsedMs=10000 speedMps=2.5 parked=false fresh=true';my $p=parse_speed($line);
is($p->{speed},9,'only genuine SDK audit speed parsed and converted to kph');
ok(!parse_speed('1794481200.250 I/VehicleApiDdsEmu: action=web-set detail=speed_kph=7 accelerator_pct=100'),'DDS Console settings cannot drive GNSS');
for my $bad('unrelated speed_kph=7',map {"1794481200.250 I/MajoongAudit: event=vehicle_sample seq=0 elapsedMs=10000 speedMps=$_ parked=false fresh=true"} ('NaN','Inf','-1','70','2.5bad')){my $v=parse_speed($bad);ok(!$v||$v->{error},'untrusted/invalid SDK speed rejected')}
is(parse_speed('1794481200.250 I/MajoongAudit: event=vehicle_sample seq=0 elapsedMs=10000 speedMps=unknown parked=false fresh=false')->{error},'invalid_sdk_vehicle_sample','unknown/stale SDK read immediately invalidates drive input');
my $s={epoch=>100,received=>10,speed=>36,seen=>1};
is(fresh_sample($s,103,13),undef,'3-second wall and monotonic edge valid');
is(fresh_sample($s,103.001,12),'stale_vehicle_speed','wall expiry');
is(fresh_sample($s,101,13.001),'stale_vehicle_speed','monotonic expiry despite clock rollback');
is(fresh_sample($s,97.99,10),'stale_vehicle_speed','future clock beyond2 seconds rejected');
is(fresh_sample($s,100,9.99),'stale_vehicle_speed','backwards monotonic time rejected');
is(fresh_sample(undef,100,10),'no_vehicle_speed_observed','missing speed fails closed');
is(fresh_sample({%$s,speed=>-1},100,10),'invalid_vehicle_speed','negative speed fails closed');
ok(accept_sample(undef,{epoch=>100,speed=>7},100),'initial fresh sample accepted');
ok(!accept_sample({epoch=>100},{epoch=>100,speed=>7},100),'duplicate epoch rejected');
ok(!accept_sample({epoch=>100},{epoch=>99,speed=>7},100),'older epoch rejected');
ok(!accept_sample(undef,{epoch=>96,speed=>7},100),'stale streamed sample rejected');
my @v=advance(0,undef,$s,8,9,10,100);is_deeply(\@v,[0,undef,0],'lead accumulates neither distance nor speed');
@v=advance(0,undef,{%$s,received=>10.4},9.5,10.5,10,100);cmp_ok(abs($v[0]-1),'<',0.00001,'first sample cannot invent earlier motion');
@v=advance(5,36,{%$s,speed=>72,received=>10.5},10.5,11.5,10,100);cmp_ok(abs($v[0]-20),'<',0.00001,'integrates observed speed trapezoid');
@v=advance($TrialCore::DISTANCE-1,36,$s,10,11,10,100);is($v[0],$TrialCore::DISTANCE,'stops at route boundary');
my $died=eval{advance(0,undef,$s,13,14,10,104);1};ok(!$died&&$@=~/stale_vehicle_speed/,'stale sample throws before another GNSS pair');
is(lease_reason(10,13,0),undef,'control lease3-second edge');
is(lease_reason(10,13.01,0),'control_lease_stale','connection timeout independent of speed');
is(lease_reason(10,11,1),'control_connection_lost','stdin EOF immediately stops input');
is(lease_reason(undef,11,0),'control_lease_stale','no control lease never starts');
my @pair=nmea_pair(48.1173,11.5167,1794481200,0,284);
is(scalar(@pair),2,'complete GGA/RMC pair generated');
for my $n(@pair){ok($n=~/\A\$GP(?:GGA|RMC),[A-Za-z0-9,.\-]*\*[0-9A-F]{2}\z/,'restricted emulator-only NMEA syntax');my($body,$hex)=$n=~/\A\$(.*)\*([0-9A-F]{2})\z/;my $xor=0;$xor^=ord($_)for split //,$body;is(sprintf('%02X',$xor),$hex,'independent checksum verification')}
my @low=point(-10);is_deeply(\@low,\@TrialCore::START,'no route undershoot');my @high=point($TrialCore::DISTANCE+1);cmp_ok(abs($high[0]-$TrialCore::END[0]),'<',1e-10,'no route overshoot');
ok(parse_lease('LEASE 100 2',102,1),'fresh timestamped increasing control lease');
ok(!parse_lease('LEASE 100 2',104,1),'buffered old lease cannot become fresh on receive');
ok(!parse_lease('LEASE 100 2',101,2),'duplicate lease sequence rejected');
ok(!parse_lease('LEASE 100 2',97,1),'future source clock beyond2seconds rejected');
ok(!parse_lease('LEASE 100 2;bad',101,1),'lease protocol injection rejected');
is(fresh_sample({%$s,measured_host=>6},100,10),'stale_measurement','freshly logged stale measured SDK value rejected');
ok(!accept_sample({epoch=>100,elapsed=>500},{epoch=>101,elapsed=>500},101),'same SDK paired read cannot be replayed with new log time');
my $expected='screenrecord --size 1920x1080 --bit-rate 6000000 --time-limit 120 /data/local/tmp/galaxy-only-a.mp4';my $raw=join("\0",split(/ /,$expected))."\0";
ok(record_identity_ok($raw,$expected),'exact owned screenrecord argv accepted');
(my $wrong=$raw)=~s/a\.mp4/a.mp4.other/;ok(!record_identity_ok($wrong,$expected),'record path substring cannot authorize cleanup');
(my $other=$raw)=~s/screenrecord/other-program/;ok(!record_identity_ok($other,$expected),'other guest process refused');
my %children;
is(bounded_command(\%children,1,'/usr/bin/perl','-e','print "okay"'),'okay','bounded transport success');is(scalar(keys%children),0,'successful child reaped');
for my $program('close STDOUT; select undef,undef,undef,2','select undef,undef,undef,2'){
 my $began=time;my $done=eval{bounded_command(\%children,0.08,'/usr/bin/perl','-e',$program);1};ok(!$done&&$@=~/command_timeout/,'transport deadline includes stdout EOF plus waitpid');cmp_ok(time-$began,'<',1,'timeout bounded under1second');is(scalar(keys%children),0,'timed out own child terminated and reaped');
}
my $done=eval{bounded_command(\%children,1,'/usr/bin/perl','-e','exit 7');1};ok(!$done&&$@=~/command_failed/,'nonzero child fails closed');is(scalar(keys%children),0,'failed child reaped');
my $parent=getppid();my %not_child=($parent=>1);stop_owned(\%not_child,$parent);ok(kill(0,$parent),'nonchild/reused PID not signalled');is(scalar(keys%not_child),0,'nonchild tracking entry discarded');
open my $f,'<',"$FindBin::Bin/galaxy_trial.pl"or die$!;local $/;my $src=<$f>;close$f;
unlike($src,qr/\b(?:python3|ssh|inject-vhal-event|driving_regulation|enable-uxr)\b/,'target runtime has no source SSH/Python or gate bypass command');
like($src,qr/\$SIG\{HUP\}.*\$SIG\{PIPE\}/,'foreground HUP/PIPE stop path exists');
like($src,qr/--lease-stdin/,'live control lease required');
like($src,qr/owned_stop\(\$_\) for keys %children/,'cleanup constrained to explicit own children');
for my $n(@pair){like($n,qr/\A\$GP(?:GGA|RMC),[0-9]{6},/,'GNSS time is integer HHMMSS for existing ranchu parser')}
like($src,qr/post_endpoint_capture_complete/,'post-end P0 capture phase retained');
like($src,qr/minimum_continuous_sdk_p0_seconds=>30/,'post-endpoint captures at least30seconds of genuine P0');
like($src,qr/record_cleanup_refused.*emulator_identity_changed/,'cleanup refuses replacement emulator identity');
is(parse_speed('1794481200.250 I/MajoongAudit: event=vehicle_sample_end seq=0 elapsedMs=10000 expired=true stopped=false fresh=false')->{error},'sdk_vehicle_sample_ended','sampler end clears motion permission immediately');
like($src,qr/request_test_shutdown\(\$stop\?'guard_interrupted':'guard_expired'\)/,'guard HUP/TERM/PIPE path also requests owned-emulator shutdown');
is(gate_reason(%normal,battery=>30),'normal','battery30percent edge remains allowed');
# Android14 goldfish GnssHwListener.cpp:169-176 performs13 assignments;
# %n is deliberately not counted. This parser-contract check is independent
# of checksum validation and requires integer HHMMSS plus mandatory variation/EW.
sub goldfish_rmc_assignments {
 my($line)=@_;my $f=qr/[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[Ee][+-]?[0-9]+)?/;
 my @fields=($line=~/\A\$GPRMC,([0-9]{6}),([^,]),([0-9]+)\.([0-9]+),([^,]),([0-9]+)\.([0-9]+),([^,]),($f),($f),([0-9]+),($f),([^,*])\*[0-9A-F]{2}\z/);return @fields;
}
my($rmc)=grep{/\A\$GPRMC,/}@pair;
my @hal=goldfish_rmc_assignments($rmc);
is(scalar(@hal),13,'canonical RMC satisfies13 goldfish sscanf assignments independently of checksum');
is(0+$hal[11],0,'mandatory RMC magnetic variation parses as float0.0');
is($hal[12],'E','mandatory magnetic-variation direction immediately precedes checksum');
like($hal[0],qr/\A[0-9]{6}\z/,'goldfish time conversion is integer HHMMSS');
my $broken=$rmc;$broken=~s/,[^,*]+,[^,*]+\*[0-9A-F]{2}\z/,,,A/;
$broken=~s/\A\$//;my $badxor=0;$badxor^=ord($_)for split//,$broken;$broken=sprintf('$%s*%02X',$broken,$badxor);
is(scalar(goldfish_rmc_assignments($broken)),0,'old blank variation tail rejected even after recomputing a valid checksum');
my $fractional=$rmc;$fractional=~s/(\A\$GPRMC,[0-9]{6}),/$1.00,/;
$fractional=~s/\*[0-9A-F]{2}\z//;$fractional=~s/\A\$//;my $fxor=0;$fxor^=ord($_)for split//,$fractional;$fractional=sprintf('$%s*%02X',$fractional,$fxor);
is(scalar(goldfish_rmc_assignments($fractional)),0,'fractional HHMMSS rejected despite valid recomputed checksum');

# Exercise actual argument validation. The impossible UID prevents any runtime
# device/guard action even if these tests run on Galaxy; only account queries occur.
sub validation_output {
 my(@flags)=@_;my%own;
 return bounded_command(\%own,3,'/usr/bin/perl','-e','open STDERR, ">&", STDOUT; system($^X,@ARGV); exit 0',"$FindBin::Bin/galaxy_trial.pl",'--pid','2','--uid','999999','--start-time','Thu Nov 12 20:00:00 2026',@flags);
}
like(validation_output('--mode','guard','--seconds','1801','--shutdown-test-emulator'),qr/Bounded seconds/,'actual CLI rejects guard duration1801');
unlike(validation_output('--mode','guard','--seconds','1800','--shutdown-test-emulator'),qr/Bounded seconds/,'actual CLI accepts guard duration1800 before impossible-UID runtime gate');
like(validation_output('--mode','check','--seconds','301'),qr/Bounded seconds/,'actual CLI retains nonguard300second cap');
like(validation_output('--mode','drive','--seconds','91','--lease-stdin'),qr/Drive duration must not exceed90/,'actual CLI retains drive90second cap');
like(validation_output('--mode','observe','--seconds','121','--lease-stdin'),qr/Observe\/static duration must not exceed120/,'actual CLI retains observe120second cap');
like(validation_output('--mode','static','--seconds','121','--lease-stdin'),qr/Observe\/static duration must not exceed120/,'actual CLI retains static120second cap');

# Actual CLI invokes the same production Core parser+mode policy. No device APIs.
my $sdk_end='1794481200.250 I/MajoongAudit: event=vehicle_sample_end seq=0 elapsedMs=10000 expired=true stopped=false fresh=false';
my $sdk_bad='1794481200.250 I/MajoongAudit: event=vehicle_sample seq=0 elapsedMs=10000 speedMps=unknown parked=false fresh=false';
sub sample_policy_cli {
 my($mode,$line)=@_;my%own;
 return bounded_command(\%own,2,'/usr/bin/perl',"-I$FindBin::Bin",'-MTrialCore=speed_sample_for_mode,fresh_sample','-e','my($mode,$line)=@ARGV;my$r;my$ok=eval{$r=speed_sample_for_mode($mode,$line);1};if(!$ok){print "STOP:$@";exit 0}if(!$r){print $mode eq "drive" ? "STOP:".fresh_sample(undef,100,10) : "ABSENT";exit 0}print $r->{metadata_only} ? "METADATA_ONLY:$r->{error}" : "VALID";',$mode,$line);
}
for my $mode(qw(static observe)){
 like(sample_policy_cli($mode,$sdk_end),qr/\AMETADATA_ONLY:sdk_vehicle_sample_ended/,"actual parser/CLI $mode records SDK end without stopping");
 like(sample_policy_cli($mode,$sdk_bad),qr/\AMETADATA_ONLY:invalid_sdk_vehicle_sample/,"actual parser/CLI $mode records SDK invalid without stopping");
 is(sample_policy_cli($mode,'no SDK telemetry'),'ABSENT',"actual parser/CLI $mode accepts absence for independent diagnosis");
}
like(sample_policy_cli('drive',$sdk_end),qr/\ASTOP:sdk_vehicle_sample_ended/,'actual parser/CLI drive fails closed on SDK end');
like(sample_policy_cli('drive',$sdk_bad),qr/\ASTOP:invalid_sdk_vehicle_sample/,'actual parser/CLI drive fails closed on SDK invalid');
like(sample_policy_cli('drive','no SDK telemetry'),qr/\ASTOP:no_vehicle_speed_observed/,'drive missing speed retains freshness-gate failure');

# Configuration-only child loads: never ADB, emulator, GPS, or host resource queries.
sub config_cli {
 my($key,$value)=@_;my%own;
 return bounded_command(\%own,2,'/usr/bin/perl',"-I$FindBin::Bin",'-e','my($k,$v)=@ARGV;if($v eq "__MISSING__"){delete $ENV{$k}}else{$ENV{$k}=$v}my$ok=eval{require TrialConfig;1};print $ok?"ACCEPT":"STOP:$@";',$key,$value);
}
like(config_cli('MAJOONG_TRIAL_BASE','__MISSING__'),qr/\ASTOP:Missing explicit/,'missing trial root fails closed before runtime');
like(config_cli('MAJOONG_TRIAL_MOUNT','/Volumes/Trial Disk'),qr/\ASTOP:External mount/,'ambiguous whitespace mount is rejected');
like(config_cli('MAJOONG_TRIAL_BASE','/tmp/MajoongTrial'),qr/\ASTOP:Trial root/,'internal output root is rejected');
like(config_cli('MAJOONG_AVD_NAME','wrong;command'),qr/\ASTOP:Invalid target user or AVD/,'unsafe AVD token rejected');
like(config_cli('MAJOONG_EMULATOR_PORT','5569'),qr/\ASTOP:Emulator port/,'odd emulator port rejected');
like(config_cli('MAJOONG_ROUTE_END_LON','11.5167'),qr/\ASTOP:Route endpoints/,'zero-distance route rejected before division');
done_testing();
