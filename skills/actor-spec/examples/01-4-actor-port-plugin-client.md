# Port Plugin Client Actor Specification

## Actor Purpose

Your purpose is to receive **outcome** messages from [Get It Passing](01-0-actor-get-it-passing.md) after you have sent it a `PortPluginRequest` (via `replyTo`). Full message shapes are in [messages.md](messages.md).

## Messaging Protocol

### Receives

- `AlreadyPorted`
- `Blocked`
- `PortingComplete`
- `PortingFailed`

## Workflow

1. You do not run a porting workflow yourself; you only interpret these outcomes after issuing a `PortPluginRequest` to Get It Passing.
