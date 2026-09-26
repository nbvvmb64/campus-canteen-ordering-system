Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$dataDir = Join-Path $projectRoot 'data'
$database = Join-Path $projectRoot 'java\web\var\canteen.mv.db'
$mavenRepository = Join-Path $projectRoot 'java\web\.tooling\repository'

if (Test-Path -LiteralPath $dataDir) {
    throw 'data/ already exists. Remove the local demo state yourself before bootstrapping again.'
}
if (Test-Path -LiteralPath $database) {
    throw 'A local demo database already exists. Refusing to overwrite it.'
}

New-Item -ItemType Directory -Path $dataDir | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'examples\sample-data\users.csv') -Destination $dataDir
Copy-Item -LiteralPath (Join-Path $projectRoot 'examples\sample-data\dishes.csv') -Destination $dataDir
Copy-Item -LiteralPath (Join-Path $projectRoot 'examples\sample-data\orders.csv') -Destination $dataDir
Copy-Item -LiteralPath (Join-Path $projectRoot 'examples\sample-data\order_items.csv') -Destination $dataDir

& mvn "-Dmaven.repo.local=$mavenRepository" -f (Join-Path $projectRoot 'java\web\pom.xml') -DskipTests package
if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }

$jar = Join-Path $projectRoot 'java\web\target\canteen-web-1.0.0.jar'
& java -jar $jar `
    "--canteen.project-root=$projectRoot" `
    '--canteen.mode=migrate' `
    '--canteen.legacy-console-stopped=true'
if ($LASTEXITCODE -ne 0) { throw 'Synthetic H2 migration failed.' }

Write-Output 'Synthetic H2 demo initialized. Run scripts\run-h2-demo.ps1 next.'
