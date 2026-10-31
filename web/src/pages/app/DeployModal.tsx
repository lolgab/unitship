import { useRef, useState } from 'preact/hooks'
import { ApiError, deploy } from '../../api'
import { Button, Icon, Modal, Output, Progress, useToast } from '../../components/ui'
import { bytes } from '../../format'

export function DeployModal({ app, onClose, onDone }: { app: string; onClose: () => void; onDone: () => void }) {
  const toast = useToast()
  const [file, setFile] = useState<File | null>(null)
  const [over, setOver] = useState(false)
  const [progress, setProgress] = useState<number | null>(null)
  const [result, setResult] = useState<{ ok: boolean; output: string } | null>(null)
  const input = useRef<HTMLInputElement>(null)
  const uploading = progress !== null && result === null

  const start = async () => {
    if (!file) return
    setProgress(0)
    setResult(null)
    try {
      const r = await deploy(app, file, setProgress)
      setResult(r)
      toast(`Deployed ${app}`)
    } catch (e) {
      const error = e as ApiError
      setResult({ ok: false, output: error.output ?? error.message })
    }
    onDone()
  }

  const pick = (f: File | undefined) => {
    if (f) {
      setFile(f)
      setProgress(null)
      setResult(null)
    }
  }

  return (
    <Modal
      title={`Deploy ${app}`}
      onClose={uploading ? () => {} : onClose}
      wide
      footer={
        result ? (
          <Button kind="primary" onClick={onClose}>
            Close
          </Button>
        ) : (
          <>
            <Button onClick={onClose} disabled={uploading}>
              Cancel
            </Button>
            <Button kind="primary" icon="upload" busy={uploading} disabled={!file} onClick={start}>
              Deploy
            </Button>
          </>
        )
      }
    >
      <p class="muted">
        Upload the executable that <code>snunit package</code> built for this server. It starts on its own port, and once it answers the
        edge switches to it with no downtime. If it does not start, the running release keeps serving.
      </p>
      {!result && (
        <div
          class={`dropzone ${over ? 'over' : ''}`}
          onDragOver={(e) => {
            e.preventDefault()
            setOver(true)
          }}
          onDragLeave={() => setOver(false)}
          onDrop={(e) => {
            e.preventDefault()
            setOver(false)
            pick(e.dataTransfer?.files[0])
          }}
          onClick={() => input.current?.click()}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && input.current?.click()}
        >
          <Icon name="upload" size={26} />
          {file ? (
            <div>
              <b>{file.name}</b> <span class="muted">{bytes(file.size)}</span>
            </div>
          ) : (
            <div>
              Drop the executable here or <u>choose a file</u>
            </div>
          )}
          <input ref={input} type="file" hidden onChange={(e) => pick((e.target as HTMLInputElement).files?.[0])} />
        </div>
      )}
      {progress !== null && !result && (
        <div class="upload-state">
          <Progress value={progress * 100} />
          <div class="muted">{progress < 1 ? `Uploading… ${Math.round(progress * 100)}%` : 'Uploaded. Waiting for the new release to answer…'}</div>
        </div>
      )}
      {result && (
        <>
          <div class={`note ${result.ok ? 'note-green' : 'note-red'}`}>
            <Icon name={result.ok ? 'check' : 'alert'} />
            <div>{result.ok ? 'The new release is live.' : 'The deploy failed. The running release was not touched.'}</div>
          </div>
          <Output text={result.output} ok={result.ok} />
        </>
      )}
    </Modal>
  )
}
