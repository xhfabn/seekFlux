param(
    [ValidateSet('up', 'down', 'restart', 'status', 'logs', 'check', 'init')]
    [string]$Action = 'status',
    [switch]$NoBuild,
    [switch]$FromServer
)

$ErrorActionPreference = 'Stop'
$taskPython = Get-Command python -ErrorAction Stop
$taskArguments = @('-X', 'utf8', (Join-Path $PSScriptRoot 'deploy/remote/remote.py'), $Action)
if ($NoBuild) { $taskArguments += '--no-build' }
if ($FromServer) { $taskArguments += '--from-server' }
& $taskPython.Source @taskArguments
exit $LASTEXITCODE
