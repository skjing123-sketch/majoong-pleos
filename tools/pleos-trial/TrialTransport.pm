package TrialTransport;
use strict;use warnings;use Exporter 'import';use Time::HiRes qw(sleep clock_gettime CLOCK_MONOTONIC);use POSIX qw(WNOHANG);use IO::Select;
our @EXPORT_OK=qw(bounded_command stop_owned);
sub mono{clock_gettime(CLOCK_MONOTONIC)}
sub stop_owned {
 my($all,$p)=@_;return unless $p&&$all->{$p};my $r=waitpid($p,WNOHANG);if($r==$p||$r==-1){delete$all->{$p};return}
 kill 'TERM',$p;my $end=mono()+0.5;while(mono()<$end){$r=waitpid($p,WNOHANG);if($r==$p||$r==-1){delete$all->{$p};return}sleep 0.01}
 kill 'KILL',$p;waitpid($p,0);delete$all->{$p};
}
sub bounded_command {
 my($all,$timeout,@cmd)=@_;die "bad_command\n" unless @cmd&&$cmd[0]=~m{\A/}&&$timeout>0;
 pipe(my$rd,my$wr)or die"pipe_failed\n";my$p=fork();die"fork_failed\n"unless defined$p;
 if(!$p){close$rd;open STDOUT,'>&',$wr;open STDERR,'>','/dev/null';close$wr;exec{$cmd[0]}@cmd;exit 127}
 close$wr;$all->{$p}=1;my$sel=IO::Select->new($rd);my$s='';my$end=mono()+$timeout;my$eof=0;
 while(mono()<$end){if($sel->can_read(0.02)){my$n=sysread($rd,my$chunk,4096);if(!defined$n){close$rd;stop_owned($all,$p);die"command_read_failed\n"}if(!$n){$eof=1;last}$s.=$chunk;if(length$s>262144){close$rd;stop_owned($all,$p);die"command_output_limit\n"}}}
 close$rd;unless($eof){stop_owned($all,$p);die"command_timeout\n"}
 my$status;while(mono()<$end){my$r=waitpid($p,WNOHANG);if($r==$p){$status=$?;last}if($r==-1){delete$all->{$p};die"command_child_unknown\n"}sleep 0.01}
 unless(defined$status){stop_owned($all,$p);die"command_timeout\n"}delete$all->{$p};die"command_failed\n"unless$status==0;return$s;
}
1;
