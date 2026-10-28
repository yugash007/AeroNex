[CmdletBinding()]
param(
    [switch]$Headless,
    [switch]$SelfTest,
    [switch]$Stop
)
$ErrorActionPreference = 'Stop'
$distribution = 'Ubuntu-24.04'
$windowsRunner = (Join-Path $PSScriptRoot 'aeronex_sim.py').Replace('\', '/')
$linuxRunner = & wsl.exe -d $distribution --exec wslpath -a $windowsRunner
if ($LASTEXITCODE -ne 0) { throw 'Could not locate the simulation launcher in WSL.' }
$linuxRunner = $linuxRunner.Trim()
$pythonPath = '/home/drone/aeronex-sim/.venv/bin/python'
$runnerArguments = @($linuxRunner)
if ($Headless) { $runnerArguments += '--headless' }
if ($SelfTest) { $runnerArguments += '--self-test' }
if ($Stop) { $runnerArguments += '--stop' }
& wsl.exe -d $distribution --exec $pythonPath @runnerArguments
if ($LASTEXITCODE -ne 0) { throw "Simulation exited with code $LASTEXITCODE. Check the log path above." }
