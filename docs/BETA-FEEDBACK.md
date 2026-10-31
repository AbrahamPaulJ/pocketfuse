Beta feedback for FaceFusion v4
==============================

What building Pocketfuse against the v4 API turned up. Every item was re-checked on 2026-11-01
against `v4-challenge` at `5f2004e` and the `v4` branch at `440fca7`, on Windows 11, Python 3.13,
CUDA, an RTX 3050 Laptop. Repro scripts are in [`tools/beta/`](../tools/beta); each takes the server
URL and a file holding the API key.

| # | Finding | `v4-challenge` | `v4` (`440fca7`) |
|---|---------|----------------|------------------|
| 1 | WebRTC stream: the content analyser runs concurrently on CUDA, cuDNN errors and one heap corruption crash | reproduces | not reachable (see 2) |
| 2 | WebRTC: the receive loop ends before the track opens, no frame is processed | works | reproduces |
| 3 | `/stream` dies on a model that is not downloaded; afterwards the session cannot be closed | reproduces (`500` on close) | reproduces (`409` on close, forever) |
| 4 | Pixel boost choices follow the launch model, not the session's model | reproduces | reproduces |
| 5 | `/stream` under the default `face_selector_mode = reference` processes nothing | reproduces | fixed (reference maps) |
| 6 | `DELETE /session` after a video `capture` on Windows | `404`, session stays alive | fixed (`206`, session closed); the files stay on disk |
| 7 | An expired session is deleted within a second, so the guard answers `401` instead of `426` and the refresh token dies with it | reproduces | reproduces |
| 8 | Docs: `--video-memory-strategy strict` makes API jobs about 3x slower | | |
| 9 | A JSON body that is not an object, or a numeric `api_key`, answers `500` (two of them before login) | reproduces | same code |
| 10 | Upload validation checks only the first file of a multi-file `POST /assets` | reproduces | same code |
| 11 | An expired session's temp and jobs folders are never removed | reproduces | same code |
| 12 | `DELETE /session` right after a video job races the output capture: a traceback in the log | reproduces | same code |
| 13 | Static source images are hashed again on every stream frame: about 60 ms of a frame for one 12 MP source | reproduces | same code |
| 14 | A failed job carries no reason: the client cannot tell no face, a content block and a missing model apart | reproduces | same code |
| 15 | `deep_swapper_model` defaults to a real, named person | reproduces | same code |


1. Concurrent content analysis in the WebRTC stream (CUDA)
----------------------------------------------------------

`apis/stream_manager.py` hands every received frame to a thread pool of `execution_thread_count`
workers. `content_analyser.forward_nsfw` guards `run()` with `conditional_thread_semaphore()`, which is
a real semaphore only for DirectML and MIGraphX; on CUDA several threads run the same `nsfw_1` session
at once (the face detector, by contrast, always takes `thread_semaphore()`).

Measured with the Pocketfuse Live screen (a Galaxy S21 FE sending VP8 over Wi-Fi, default thread count):

- First run: `POST /stream` answered `201`, then the server process died. Windows event log:
  `python.exe`, faulting module `ntdll.dll`, exception `0xc0000374` (heap corruption). No Python traceback.
- After a restart: 16.5 fps came back, and the server log showed 44 `CUDNN_STATUS_BAD_PARAM` lines for 22
  frames, all in node `/model.1/conv/Conv` (present in `nsfw_1.onnx` and `yoloface_8n.onnx`; the detector
  is locked, the analyser is not).
- With `--execution-thread-count 1` there were no cuDNN errors, but no frame came back either.

Suggested: take `thread_semaphore()` around `forward_nsfw` on every provider, as the detector does.
A crash here takes the whole API down for every session, so it is also a denial of service by any client
that can open a stream.


2. `v4`: WebRTC receive loop ends before the track opens
--------------------------------------------------------

`apis/stream_video.receive_video_frames` loops `while rtc.is_open(video_track) and not receive_event.is_set()`.
It is started inside `process_video`, before the answer has been returned, so the track cannot be open
yet: the loop exits at once, the peer is deleted, and no frame is processed. The same client gets 16.5 fps
on `v4-challenge` (which has no `is_open` check there) and "connection lost" on `v4`.

Suggested: wait for the track to open (with a timeout) before the idle loop, or treat "not open yet" as idle.


3. `/stream` on a model that is not downloaded, then a session that cannot be closed
-------------------------------------------------------------------------------------

Repro: `python tools/beta/stream_missing_model.py <url> <key_file> source.jpg frame.jpg ghost_1_256`
(any processor model no job has used yet).

- `PUT /state {"face_swapper_model": "ghost_1_256"}` is accepted (`200`).
- The first frame raises `AttributeError: 'NoneType' object has no attribute 'get_inputs'` in
  `face_swapper.forward_swap_face` (the inference pool is `None`, the file is missing). The socket closes
  without a close frame.
- `v4`: `DELETE /session` then answers `409 session not destroyed` immediately, after 5 s and after 30 s;
  the session stays valid. A client cannot end its own session. (`v4-challenge`: `500`, uvicorn
  "Unexpected ASGI message 'websocket.close', after sending 'websocket.close'".)

Models are only downloaded by a job (`pre_check` in `process_step`), so a client has no way to prepare
a model for streaming except running a throwaway job; Pocketfuse does exactly that.
Suggested: download (or reject with a message) when a processor model changes through `PUT /state`,
close the socket with a reason, and clear the session's busy flag when a stream dies.


4. Pixel boost choices follow the launch model
----------------------------------------------

Repro: `python tools/beta/pixel_boost_choices.py <url> <key_file>`.
Launched with the default `hyperswap_1a_256`, a session that switches to `inswapper_128_fp16` gets
`400 invalid state value` for `face_swapper_pixel_boost = 128x128`, alone or in the same request as the
model. The choices come from the launch model in `register_args`. The 128 model then runs at 256 (four
tiles per face): 15.5 s for a 49 frame clip against 9 s for hyperswap.

Suggested: validate the boost against the session's current model and list choices per model in
`/capabilities`.


5 to 8. Smaller items
---------------------

- **5, fixed on `v4`.** On `v4-challenge`, `streamer.process_stream_frame` passes no reference frame, so
  under the default `reference` mode every frame comes back unprocessed with no hint. Pocketfuse streams
  with `one` or `many`.
- **6, fixed on `v4`.** A `capture` on a video keeps an ffmpeg reader open; Windows cannot delete the
  file, and `v4-challenge` returned `404` before clearing the session, so its tokens stayed valid. `v4`
  answers `206` and closes the session; the files still stay in the temp folder until restart.
- **7. An expired session is deleted within a second, so `426` and refresh never happen.** The session
  guard is meant to answer `426 Upgrade Required` for a known but expired access token, the signal to
  refresh and resend. But `observe_api_session` starts a thread that polls `validate_api_session` and
  calls `clear_api_session` as soon as it fails (1 s poll on `v4-challenge`, 0.5 s on `v4`). After that,
  `find_api_session_id` no longer knows the token and the guard answers `401`. Even inside that window,
  `refresh_session` also requires `validate_api_session` to pass, and both tokens share one `expires_at`.
  Measured with [`tools/beta/refresh_after_expiry.py`](../tools/beta/refresh_after_expiry.py) on both
  branches: after the expiry (310 s on `v4`, 610 s on `v4-challenge`) the request answers
  `401 invalid access token`, and `PUT /session` with the refresh token answers `401 something went wrong`.
  Henry confirmed it is a bug (the session is killed too early). The fix is likely to keep an expired
  session, and to accept its refresh token, for a grace period. Until then, Pocketfuse refreshes before
  the expiry, every 3 minutes.
- **8.** The same 49 frame job: `strict` 30 s, `moderate` 9 s (models unload between frames under
  `strict`). For a long running API server `moderate` may be the better default, or worth a line in the guide.
- **9. Malformed JSON answers `500`.** `POST /session`, `PUT /session`, `PUT /state` and
  `PUT /state?action=select` call `body.get` or `body.items` on whatever `request.json()` returns, and
  `validate_api_key` hands a non-string key to `secrets.compare_digest`. The exception middleware catches
  only `JSONDecodeError`, `MultipartParseError` and `UnicodeDecodeError`, so a valid JSON array, string or
  number answers `500` with a traceback in the log; `POST /session` and `PUT /session` need no login for
  that. [`tools/beta/malformed_bodies.py`](../tools/beta/malformed_bodies.py) on `v4-challenge`: body `[]`
  on all four, and `{"api_key": 123}`, answer `500`; `POST /jobs/{id}` with `[]` answers `400` for
  comparison. Fix: an `isinstance(body, dict)` check (or a `400` for `AttributeError` and `TypeError`
  in the request guard) and an `isinstance(api_key, str)` check.
- **10. Only the first uploaded file is validated.** In `apis/asset_helper.py` `validate_asset_files`,
  the `return media_type in [...]` sits inside the loop, so files 2 to n are never checked, and
  `save_asset_files` then drops the bad one without a word. Same script: `[a.jpg, b.txt]` answers `201`
  with one asset id for two files; `[b.txt, a.jpg]` answers `400`.
- **11. Expired sessions leave their files on disk.** The expiry path (`store_manager`
  `conditional_api_destroy`, then `destroy`) clears only the in-memory stores. Only `DELETE /session`
  removes `<temp>/<session_id>` and `<jobs>/<session_id>`, and nothing sweeps them at start. A phone that
  loses its network therefore leaves its uploads and outputs on the PC for good: this PC had 27 session
  folders under `.jobs` and 13 matching temp folders (64 MB) from sessions that ended that way.
- **12. Output capture races `DELETE /session`.** `capture_output_asset` runs as a second background task
  after `run_job` has already marked the job `completed`. A client that sees `completed` and closes its
  session at once removes the temp folder while ffprobe reads the output, and `extract_video_metadata`
  raises `TypeError: float() argument must be ... not 'NoneType'` (`ffprobe.py`). Seen 8 times in our
  server log, once after every benchmark job that closed its session straight away.
- **13. Source images are re-hashed on every frame.** `face_store.get_faces` keys its cache with
  `create_hash(vision_frame.tobytes())`, a CRC32 over the whole frame. For `face_swapper` a stream or Live
  frame looks each static source up 3 times (`pre_process`, `extract_source_face`, `select_faces`), and
  every other processor once more. Measured with the server's own hash in the venv
  (`python -I -c "zlib.crc32(frame.tobytes())"` over random frames, 10 runs): 18.8 ms at 4080x3060, 5.1 ms
  at 2048x1536, 1.2 ms at 1024x768. One phone photo as the source therefore costs about 60 ms of a
  175 to 250 ms frame. Pocketfuse now scales sources to 2048 px; keying static sources by asset or path
  would fix it for every client.
- **14. A failed job says only "failed".** [`tools/beta/failed_job_reason.py`](../tools/beta/failed_job_reason.py)
  runs a swap with a source that has no face: `GET /jobs/{id}` answers `"status": "failed"` and nothing
  else, while the server log says `no source face detected!`. No endpoint serves the reason, so a client
  cannot tell a missing face, a content analyser block and a missing model apart. A short reason code on
  the step would help clients without weakening the analyser.
- **15. The deep swapper defaults to a real person.** Same script: `/capabilities` lists
  `deep_swapper_model` with the default `iperov/elon_musk_224` and 157 choices, most of them named
  celebrities. A client that renders choices generically puts a real person one tap away. A neutral
  default, or a flag that marks real-person models, would make that safer.
