import type { ComponentChildren } from 'preact'
import { useState } from 'preact/hooks'
import { ApiError, type ActionResult } from './api'
import { Button, Modal, Output, useToast } from './components/ui'

/**
 * Runs the actions of the page (restart, deploy...): one at a time, with a toast when it worked and the output of the command
 * when it did not.
 */
export function useActions(after: () => void): {
  busy: string | null
  run: (label: string, request: () => Promise<ActionResult | unknown>, done?: string) => Promise<boolean>
  modal: ComponentChildren
} {
  const toast = useToast()
  const [busy, setBusy] = useState<string | null>(null)
  const [failure, setFailure] = useState<{ title: string; message: string; output?: string } | null>(null)

  const run = async (label: string, request: () => Promise<ActionResult | unknown>, done?: string) => {
    setBusy(label)
    try {
      await request()
      toast(done ?? `${label}: done`)
      after()
      return true
    } catch (e) {
      const error = e as ApiError
      setFailure({ title: `${label} failed`, message: error.message, output: error.output })
      after()
      return false
    } finally {
      setBusy(null)
    }
  }

  const modal = failure && (
    <Modal title={failure.title} onClose={() => setFailure(null)} wide footer={<Button onClick={() => setFailure(null)}>Close</Button>}>
      {failure.output ? <Output text={failure.output} ok={false} /> : <p>{failure.message}</p>}
    </Modal>
  )
  return { busy, run, modal }
}

export function Confirm({
  title,
  children,
  confirm,
  danger,
  busy,
  onConfirm,
  onClose,
}: {
  title: string
  children: ComponentChildren
  confirm: string
  danger?: boolean
  busy?: boolean
  onConfirm: () => void
  onClose: () => void
}) {
  return (
    <Modal
      title={title}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button kind={danger ? 'danger' : 'primary'} busy={busy} onClick={onConfirm}>
            {confirm}
          </Button>
        </>
      }
    >
      {children}
    </Modal>
  )
}
