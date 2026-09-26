Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$env:PYTHONDONTWRITEBYTECODE = '1'
& python (Join-Path $projectRoot 'python\src\run_analysis.py') `
    --data-dir (Join-Path $projectRoot 'examples\sample-data') `
    --output-dir (Join-Path $projectRoot 'output') `
    --config (Join-Path $projectRoot 'python\analysis_config.json')
exit $LASTEXITCODE
