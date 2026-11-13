#!/usr/bin/perl
use strict; use warnings;
use FindBin; use lib $FindBin::Bin; use TrialCore qw(identity_ok gate_reason parse_speed fresh_sample advance point nmea_pair safe_output lease_reason accept_sample parse_lease record_identity_ok speed_sample_for_mode);
use TrialTransport qw(bounded_command stop_owned);
use Errno qw(ESRCH);
use Getopt::Long qw(GetOptions); use Time::HiRes qw(time sleep clock_gettime CLOCK_MONOTONIC); use POSIX qw(WNOHANG); use IO::Select; use JSON::PP; use File::Path qw(make_path);
my $base=$TrialCore::BASE; my $mount=$TrialConfig::MOUNT; my $adb="$base/sdk/platform-tools/adb";
my($mode,$pid,$uid,$start,$out,$seconds,$lead,$record,$shutdown,$lease_stdin)=('check',0,-1,'','',30,5,0,0,0);
GetOptions('mode=s'=>\$mode,'pid=i'=>\$pid,'uid=i'=>\$uid,'start-time=s'=>\$start,'out=s'=>\$out,'seconds=i'=>\$seconds,'lead=i'=>\$lead,'record!'=>\$record,'shutdown-test-emulator!'=>\$shutdown,'lease-stdin!'=>\$lease_stdin) or die "Bad arguments\n";
die "Use --mode check|observe|static|drive|guard\n" unless $mode=~/\A(?:check|observe|static|drive|guard)\z/;
die "Explicit new PID/UID/start time required\n" unless $pid>1 && $uid>=0 && length($start)>10;
die "Bounded seconds must be10..1800 for guard or10..300 otherwise; drive max90; lead0..10\n" unless $seconds>=10&&$seconds<=($mode eq 'guard'?1800:300)&&$lead>=0&&$lead<=10;
die "Drive duration must not exceed90 seconds\n" if $mode eq 'drive'&&$seconds>90;
die "Observe/static duration must not exceed120 seconds\n" if $mode=~/observe|static/&&$seconds>120;
die "Recording only belongs to observe/static/drive\n" if $record&&$mode=~/check|guard/;
die "Shutdown flag belongs only to guard\n" if $shutdown&&$mode ne 'guard';
die "Guard must explicitly own bounded emulator shutdown\n" if $mode eq 'guard'&&!$shutdown;
die "Observe/static/drive require --lease-stdin and a foreground SSH lease stream\n" if $mode=~/observe|static|drive/&&!$lease_stdin;
my $stop=0; my %children; my($stream,$stream_pid,$guest_record_pid,$guest_record_path,$guest_record_cmd); my $record_finalized=0;
$SIG{TERM}=sub{$stop=1};$SIG{INT}=sub{$stop=1};$SIG{HUP}=sub{$stop=1};$SIG{PIPE}=sub{$stop=1};
sub mono {clock_gettime(CLOCK_MONOTONIC)}
sub owned_stop {stop_owned(\%children,$_[0])}
sub command {bounded_command(\%children,@_)}
sub trim {my($s)=@_;$s=~s/^\s+|\s+$//g;return $s}
sub identity {
 my $got={pid=>$pid,uid=>trim(command(2,'/bin/ps','-p',$pid,'-o','uid=')),start=>trim(command(2,'/bin/ps','-p',$pid,'-o','lstart=')),command=>trim(command(2,'/bin/ps','-p',$pid,'-o','command='))};
 return identity_ok($got,{pid=>$pid,uid=>$uid,start=>$start});
}
my $identity_initial=0;my $next_mount_check=0;my $mount_good=0;
sub resources {
 return 'identity_mismatch' unless identity();
 if(mono()>=$next_mount_check){my $plist=command(3,'/usr/sbin/diskutil','info','-plist',$mount);$mount_good=$plist=~m{<key>MountPoint</key>\s*<string>\Q$mount\E</string>}&&$plist=~m{<key>Internal</key>\s*<false\s*/>}&&$plist=~m{<key>FilesystemType</key>\s*<string>apfs</string>};$next_mount_check=mono()+3}
 my $pressure=trim(command(2,'/usr/sbin/sysctl','-n','kern.memorystatus_vm_pressure_level'));
 my $di=command(2,'/bin/df','-k','/System/Volumes/Data');my $de=command(2,'/bin/df','-k',$mount);
 my @di=split /\s+/,(split /\n/,$di)[1]//'';my @de=split /\s+/,(split /\n/,$de)[1]//'';
 my $power=command(2,'/usr/bin/pmset','-g','batt');my($battery)=$power=~/\b(\d{1,3})%;/;
 return gate_reason(identity=>1,mount=>$mount_good,pressure=>$pressure,internal=>$di[3],external=>$de[3],battery=>$battery);
}
my $logfh;
sub request_test_shutdown {
 my($reason)=@_;my $same=eval{identity()};if(!$same){event('shutdown_refused_identity_changed',reason=>$reason);return}
 my $accepted=eval{adb('emu','kill');1};my $confirmed=0;my $until=mono()+5;
 while(mono()<$until){if(!kill(0,$pid)&&$! == ESRCH){$confirmed=1;last}sleep 0.1}
 event('owned_emulator_shutdown_result',reason=>$reason,adb_request_accepted=>$accepted?JSON::PP::true:JSON::PP::false,confirmed=>$confirmed?JSON::PP::true:JSON::PP::false,operator_recheck_required=>$confirmed?JSON::PP::false:JSON::PP::true);
}

sub event {my($name,%x)=@_;my $s=JSON::PP->new->canonical->encode({event=>$name,host_epoch=>0+sprintf('%.3f',time),%x})."\n";print $logfh $s if $logfh;print $s;}
sub adb {command(4,$adb,'-P',$TrialConfig::ADB_PORT,'-s',$TrialConfig::SERIAL,@_)}
sub assert_resources {my $reason=eval {resources()};$reason='resource_query_error' if $@;die "$reason\n" unless defined($reason)&&$reason eq 'normal'}
my($lease_last,$lease_epoch,$lease_seq);my $lease_buf='';
sub update_lease {
 return unless $lease_stdin;
 my $ready=IO::Select->new(\*STDIN);
 while($ready->can_read(0)){my $n=sysread(STDIN,my $chunk,4096);die "control_connection_lost\n" unless defined($n)&&$n>0;$lease_buf.=$chunk;die "control_protocol_error\n" if length($lease_buf)>1024;while($lease_buf=~s/\A([^\n]*)\n//){my $lease=parse_lease($1,time,$lease_seq);die "control_protocol_error\n" unless $lease;($lease_epoch,$lease_seq,$lease_last)=($lease->{epoch},$lease->{seq},mono())}}
 die "control_lease_stale\n" if defined($lease_epoch)&&(time-$lease_epoch>3||time-$lease_epoch < -2);
 my $reason=lease_reason($lease_last,mono(),0);die "$reason\n" if $reason;
}
my $err;
eval { RUN: {
 die "Wrong target account or architecture\n" unless trim(command(2,'/usr/bin/id','-un')) eq $TrialConfig::TARGET_USER&&trim(command(2,'/usr/bin/uname','-m')) eq 'arm64';
 die "UID does not match the active target account\n" unless trim(command(2,'/usr/bin/id','-u'))==$uid;
 $start=trim($start);assert_resources();die "Device not ready\n" unless trim(adb('get-state')) eq 'device';
 if($mode eq 'check'){event('check_passed',new_pid=>$pid,uid=>$uid,mode=>'readonly',arrival_verified=>JSON::PP::false);last RUN}
 die "Unsafe/new output path required\n" unless safe_output($out)&&!-e $out;
 my $real=trim(command(2,'/bin/pwd','-P')); # current working directory is not used for any artifact
 for my $parent($base,"$base/verification"){die "Symlinked trial parent\n" if -l $parent}
 make_path($out,{mode=>0700});open $logfh,'>',"$out/events.jsonl" or die "event_log_failed\n";chmod 0600,"$out/events.jsonl";
 event('boundary',execution_host=>'Galaxy',driver=>'Perl-direct-local-ADB',source=>'existing backend plus lightweight SSH/proxy and separately measured official Console UI',speed_observation=>'MajoongAudit event=vehicle_sample from successful genuine Car SDK paired read; DDS web-set ignored',arrival_verified=>JSON::PP::false);
 if($mode eq 'guard'){
  my $end=mono()+$seconds;my $guard_fault=0;
  while(!$stop&&mono()<$end){my $reason=eval {resources()};$reason='resource_query_error' if $@||!defined($reason);if($reason ne 'normal'){$guard_fault=1;open my $sf,'>',"$out/STOP";print $sf "$reason\n";close $sf;event('guard_stop',reason=>$reason);if($shutdown){request_test_shutdown($reason)}last}sleep 1}
  if($shutdown&&!$guard_fault){request_test_shutdown($stop?'guard_interrupted':'guard_expired')}event('guard_finished');last RUN;
 }
 if($lease_stdin){my $until=mono()+3;while(!defined($lease_last)&&mono()<$until){eval{update_lease()};die "control_connection_lost\n" if $@=~/control_connection_lost|control_protocol_error/;sleep 0.05}update_lease()}
 die "Emulator not boot complete\n" unless trim(adb('shell','getprop','sys.boot_completed')) eq '1';
 if($record){
  my $name=$out;$name=~s{.*/}{};$guest_record_path="/data/local/tmp/$name.mp4";
  my $limit=$seconds+$lead+($mode eq 'drive'?70:20);$limit=180 if $limit>180;
  $guest_record_cmd="screenrecord --size 1920x1080 --bit-rate 6000000 --time-limit $limit $guest_record_path";
  my $shell="$guest_record_cmd >/data/local/tmp/$name-record.log 2>&1 & echo \$!";
  my $response=trim(adb('shell',$shell));die "record_pid_invalid\n" unless $response=~/\A[0-9]+\z/;$guest_record_pid=0+$response;
  sleep 0.25;my $cmd=adb('shell','cat',"/proc/$guest_record_pid/cmdline");die "record_process_not_verified\n" unless record_identity_ok($cmd,$guest_record_cmd);
  event('record_started',guest_pid=>$guest_record_pid,size=>'1920x1080');
 }
 my $uptime_before=mono();my $uptime=adb('shell','cat','/proc/uptime');my $uptime_after=mono();die "uptime_calibration_uncertain\n" unless $uptime=~/\A([0-9.]+) /&&$uptime_after-$uptime_before<=1;my $guest_to_host=($uptime_before+$uptime_after)/2-$1;
 pipe(my $rd,my $wr) or die "log_pipe_failed\n";$stream_pid=fork();die "log_fork_failed\n" unless defined $stream_pid;
 if(!$stream_pid){close $rd;open STDOUT,'>&',$wr;open STDERR,'>','/dev/null';close $wr;exec {$adb} $adb,'-P',$TrialConfig::ADB_PORT,'-s',$TrialConfig::SERIAL,'logcat','-T','1','-v','epoch','VehicleApiDdsEmu:I','MajoongNmeaProbe:I','MajoongProbe:I','MajoongAudit:I','*:S';exit 127}
 close $wr;$children{$stream_pid}=1;$stream=$rd;my $select=IO::Select->new($stream);my $buf='';my $logbytes=0;
 open my $metadata,'>',"$out/metadata-logcat.txt" or die "metadata_log_failed\n";chmod 0600,"$out/metadata-logcat.txt";
 my($sample,$metres,$previous,$count)=(undef,0,undef,0);my $last=mono();my $drive=$last+$lead;my $drive_deadline=$drive+$seconds;my $deadline=$drive_deadline+($mode eq 'drive'?60:0);my $next_fix=$last;my $next_gate=0;my($endpoint_at,$parked_at);my $post_complete=0;
 event('trial_started',mode=>$mode,lead=>$lead,seconds=>$seconds,route_metres=>0+sprintf('%.2f',$TrialCore::DISTANCE),synthetic_gnss=>JSON::PP::true);
 while(!$stop&&mono()<$deadline){
  update_lease();
  if(mono()>=$next_gate){assert_resources();die "external_stop_requested\n" if -f "$out/STOP";$next_gate=mono()+1}
  if($select->can_read(0.05)){
   my $n=sysread($stream,my $chunk,8192);die "speed_stream_read_error\n" unless defined $n;die "speed_stream_disconnected\n" unless $n;$buf.=$chunk;die "metadata_line_limit\n" if length($buf)>65536;
   while($buf=~s/\A([^\n]*)\n//){my $line=$1;$logbytes+=length($line)+1;die "metadata_log_limit\n" if $logbytes>1048576;print $metadata "$line\n";
    my $parsed=speed_sample_for_mode($mode,$line);next unless $parsed;if($parsed->{metadata_only}){event('sdk_speed_metadata_only',mode=>$mode,reason=>$parsed->{error});next}my $now=mono();next unless accept_sample($sample,$parsed,time);
    $sample={%$parsed,seen=>1,received=>$now,measured_host=>$parsed->{elapsed}/1000+$guest_to_host};event('speed_observed',remote_epoch=>$sample->{epoch},kph=>$sample->{speed},source=>'genuine Car SDK paired read');
   }
  }
  my $now=mono();next if $mode eq 'observe'||$now<$next_fix;
  if($mode eq 'drive'&&!defined($endpoint_at)&&$now>=$drive_deadline){die "drive_timeout_without_endpoint\n"}
  if(defined($endpoint_at)){
   my $fresh=fresh_sample($sample,time,$now);die "$fresh\n" if $fresh;
   if($sample->{parked}&&$sample->{speed}==0){if(!defined($parked_at)){$parked_at=$now;event('post_endpoint_sdk_p0_observed')}}else{$parked_at=undef}
   if(defined($parked_at)&&$now-$parked_at>=30){$post_complete=1;event('post_endpoint_capture_complete',parked_seconds=>30,arrival_verified=>JSON::PP::false);last}
   die "parking_not_confirmed_within30s\n" if !defined($parked_at)&&$now-$endpoint_at>30;
   die "post_endpoint_capture_limit\n" if $now-$endpoint_at>60;
  }
  if($mode eq 'drive'){if(!$sample&&$now-$last<=3){next}my $initial_fresh=fresh_sample($sample,time,$now);die "$initial_fresh\n" if $initial_fresh}
  my $kph=0;
  ($metres,$previous,$kph)=advance($metres,$previous,$sample,$last,$now,$drive,time) if $mode eq 'drive';$last=$now;my($lat,$lon)=point($metres);my $sent=0;
  for my $sentence(nmea_pair($lat,$lon,time,$kph,$TrialCore::BEARING)){
   die "interrupted\n" if $stop;update_lease();assert_resources();die "interrupted\n" if $stop;update_lease();my $fresh=fresh_sample($sample,time,mono());die "$fresh\n" if $mode eq 'drive'&&$now>=$drive&&$fresh;
   my $response=adb('emu','geo','nmea',$sentence);die "nmea_console_rejected\n" unless $response=~/\bOK\b/;++$sent;
  }
  die "incomplete_nmea_pair\n" unless $sent==2;++$count;event('pair_complete',pair=>$count,progress=>0+sprintf('%.4f',$metres/$TrialCore::DISTANCE),kph=>$kph,phase=>defined($endpoint_at)?'post-end':($now<$drive?'lead':'drive'));
  if($mode eq 'drive'&&$metres>=$TrialCore::DISTANCE&&!defined($endpoint_at)){$endpoint_at=$now;event('endpoint_reached_capture_continues',minimum_continuous_sdk_p0_seconds=>30)}$next_fix=$now+1;
 }
 close $metadata;die "post_endpoint_capture_incomplete\n" if $mode eq 'drive'&&!$post_complete&&!$stop;event('trial_finished',pairs=>$count,interrupted=>$stop?JSON::PP::true:JSON::PP::false,arrival_verified=>JSON::PP::false);
} };$err=$@;
# Only children created by this runner are stopped. Never enumerate/kill user's other processes.
owned_stop($_) for keys %children;
if($guest_record_pid){
 my $still_owned=eval{identity()};
 if(!$still_owned){event('record_cleanup_refused',reason=>'emulator_identity_changed');}
 else {
  eval {my $cmd=adb('shell','cat',"/proc/$guest_record_pid/cmdline");if(record_identity_ok($cmd,$guest_record_cmd)){adb('shell','kill','-2',$guest_record_pid);sleep 1}};
  my $owns_for_pull=eval{identity()};
  if($owns_for_pull){eval {command(30,$adb,'-P',$TrialConfig::ADB_PORT,'-s',$TrialConfig::SERIAL,'pull',$guest_record_path,"$out/actual-screen.mp4");die "record_file_empty\n" unless -s "$out/actual-screen.mp4";event('record_pulled',path=>"$out/actual-screen.mp4",bytes=>-s "$out/actual-screen.mp4");$record_finalized=1};event('record_unverified',reason=>'pull_or_finalize_failed') if $@}
  else {event('record_cleanup_refused',reason=>'emulator_identity_changed_before_pull')}
 }
}

if($err){$err=~s/\n.*//s;$err='diagnostic_error' unless $err=~/\A[A-Za-z0-9_ ]+\z/;event('stopped',reason=>$err,arrival_verified=>JSON::PP::false);close $logfh if $logfh;exit 1}
close $logfh if $logfh;exit 0;
