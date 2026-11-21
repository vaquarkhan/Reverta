$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root "mcp-tx-proxy\target\tx-engine.jar"
if (-not (Test-Path $jar)) {
    mvn -B -f (Join-Path $root "pom.xml") -pl mcp-tx-proxy -am package -DskipTests
}
java -jar $jar demo --port 8080
