Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$jar = Join-Path $projectRoot 'java\web\target\canteen-web-1.0.0.jar'
$mavenRepository = Join-Path $projectRoot 'java\web\.tooling\repository'
if (-not (Test-Path -LiteralPath $jar)) {
    & mvn "-Dmaven.repo.local=$mavenRepository" -f (Join-Path $projectRoot 'java\web\pom.xml') -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }
}

& java -jar $jar "--canteen.project-root=$projectRoot" '--canteen.mode=serve'
exit $LASTEXITCODE
