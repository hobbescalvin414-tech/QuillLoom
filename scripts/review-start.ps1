param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectId
)

$ErrorActionPreference = "Stop"

mvn spring-boot:run `
  "-Dspring-boot.run.profiles=dev" `
  "-Dspring-boot.run.arguments=--spring.main.web-application-type=none --quillloom.postdraft.review.runtime.cli-enabled=true --quillloom.postdraft.review.runtime.cli-action=start --quillloom.postdraft.review.runtime.cli-project-id=$ProjectId"
