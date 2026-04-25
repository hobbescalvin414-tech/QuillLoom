param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectId,

    [Parameter(Mandatory = $true)]
    [string]$HumanReviewNote
)

$ErrorActionPreference = "Stop"

$previousHumanReviewNote = $env:QUILLLOOM_POSTDRAFT_REVIEW_RUNTIME_CLI_HUMAN_REVIEW_NOTE

try {
    $env:QUILLLOOM_POSTDRAFT_REVIEW_RUNTIME_CLI_HUMAN_REVIEW_NOTE = $HumanReviewNote

    mvn spring-boot:run `
      "-Dspring-boot.run.profiles=dev" `
      "-Dspring-boot.run.arguments=--spring.main.web-application-type=none --quillloom.postdraft.review.runtime.cli-enabled=true --quillloom.postdraft.review.runtime.cli-action=resume --quillloom.postdraft.review.runtime.cli-project-id=$ProjectId"
}
finally {
    if ($null -eq $previousHumanReviewNote) {
        Remove-Item Env:\QUILLLOOM_POSTDRAFT_REVIEW_RUNTIME_CLI_HUMAN_REVIEW_NOTE -ErrorAction SilentlyContinue
    } else {
        $env:QUILLLOOM_POSTDRAFT_REVIEW_RUNTIME_CLI_HUMAN_REVIEW_NOTE = $previousHumanReviewNote
    }
}
