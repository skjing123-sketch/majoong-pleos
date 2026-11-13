package TrialConfig;
use strict; use warnings;
use POSIX qw(isfinite);
sub required { my($key)=@_; my$v=$ENV{$key}; die "Missing explicit $key configuration\n" unless defined($v)&&length($v); return $v; }
our $MOUNT=required('MAJOONG_TRIAL_MOUNT');
our $BASE=required('MAJOONG_TRIAL_BASE');
die "External mount must be /Volumes/NAME without whitespace\n" unless $MOUNT=~m{\A/Volumes/[A-Za-z0-9._-]+\z};
die "Trial root must be strictly below the external mount\n" unless $BASE=~m{\A\Q$MOUNT\E/[A-Za-z0-9._/-]+\z} && $BASE!~m{(?:\A|/)\.\.?(?:/|\z)} && $BASE!~m{/\z};
our $TARGET_USER=required('MAJOONG_TARGET_USER');
our $AVD=required('MAJOONG_AVD_NAME');
die "Invalid target user or AVD name\n" unless $TARGET_USER=~/\A[A-Za-z0-9_-]+\z/ && $AVD=~/\A[A-Za-z0-9_-]+\z/;
our $ADB_PORT=$ENV{MAJOONG_ADB_PORT}//15037;
our $EMULATOR_PORT=$ENV{MAJOONG_EMULATOR_PORT}//5568;
for my $v($ADB_PORT,$EMULATOR_PORT){die "Invalid dedicated port\n" unless $v=~/\A[0-9]+\z/ && $v>=1024 && $v<=65534;}
die "Emulator port must be even and distinct from the dedicated ADB port\n" unless $EMULATOR_PORT%2==0 && $ADB_PORT!=$EMULATOR_PORT && $ADB_PORT!=$EMULATOR_PORT+1;
our $SERIAL="emulator-$EMULATOR_PORT";
sub coordinate { my($key,$limit)=@_;my$v=required($key);die "Invalid route coordinate\n" unless $v=~/\A-?[0-9]+(?:\.[0-9]+)?\z/&&isfinite(0+$v)&&abs($v)<=$limit;return 0+$v; }
our @START=(coordinate('MAJOONG_ROUTE_START_LAT',90),coordinate('MAJOONG_ROUTE_START_LON',180));
our @END=(coordinate('MAJOONG_ROUTE_END_LAT',90),coordinate('MAJOONG_ROUTE_END_LON',180));
die "Route endpoints must differ\n" if $START[0]==$END[0]&&$START[1]==$END[1];
1;
