import { memo } from 'react';
import { flushSync } from 'react-dom';
import { createRoot } from 'react-dom/client';
import { AssistantMessageBody, MessageAuthorHeading, UserMessageBubble, WorkLogLine } from '@t3-chat/ChatTimelineParts';
import { PermissionModes, SpeedSegments, senderRuntimeModes } from '@t3-chat/ChatSenderOptions';

function Icon({id, className = 'icon'}) {
  return <svg aria-hidden="true" className={className}><use href={`#${id}`}/></svg>;
}

const glyph = {command: 'terminal', explore: 'search-work', edit: 'file-work'};

function workLabel(items, active) {
  const count = items.length;
  const kinds = new Set(items.map((item) => item.work.kind));
  const kind = kinds.size === 1 ? items[0].work.kind : '';
  if (kind === 'reasoning') return [active ? 'Thinking' : 'Reasoning', kind];
  if (kind === 'plan') return ['Plan', kind];
  if (kind === 'command') return [`${active ? 'Running' : 'Ran'} ${count === 1 ? 'a command' : `${count} commands`}`, kind];
  if (kind === 'explore') return [active ? 'Exploring' : 'Explored', kind];
  if (kind === 'edit') return [active ? 'Editing files' : 'Edited files', kind];
  return [active ? 'Working' : count === 1 ? 'Used a tool' : `Used ${count} tools`, kind];
}

const WorkGroup = memo(function WorkGroup({rows, markdown}) {
  const active = rows.some((row) => row.work.state === 'running');
  const failed = rows.some((row) => ['failed', 'interrupted'].includes(row.work.state));
  const [title, kind] = workLabel(rows, active);
  const label = title + (failed && !active ? rows.some((row) => row.work.state === 'failed') ? ' · Error' : ' · Stopped' : '');
  return <details className="work-group" data-state={active ? 'running' : failed ? 'failed' : 'completed'}>
    <summary><Icon id="chevron-right" className="icon work-chevron"/>
      <WorkLogLine icon={<Icon id={glyph[kind] ?? 'list-work'}/>} label={label}/>
    </summary>
    <div className="work-items">{rows.map((row) => <div className="work-item" key={row.id} data-state={row.work.state}>
      <WorkLogLine label={row.work.label} wrapLabel/>
      {row.text && (kind === 'reasoning' || kind === 'plan'
        ? <div className="markdown" dangerouslySetInnerHTML={{__html: markdown(row.text)}}/>
        : <pre className="work-output">{row.text}</pre>)}
    </div>)}</div>
  </details>;
});

const Message = memo(function Message({row, markdown}) {
  if (row.role === 'You') return <article className="message message-user" aria-label="You">
    <UserMessageBubble className="t3-user-bubble bg-message p-3"><MessageAuthorHeading>You</MessageAuthorHeading>
      <div className="markdown">{row.text}</div>
    </UserMessageBubble>
  </article>;
  if (row.role === 'Status' || row.role === 'Error') return <article className={`message message-note${row.role === 'Error' ? ' message-error' : ''}`}>{row.text}</article>;
  return <article className="message message-assistant" aria-label={row.role}>
    <AssistantMessageBody><div className="markdown" dangerouslySetInnerHTML={{__html: markdown(row.text)}}/></AssistantMessageBody>
  </article>;
});

function mountTimeline(element, markdown) {
  const root = createRoot(element);
  const byId = new Map();
  const groups = [];
  let legacyId = 0;
  function apply(rows, settled) {
    const touched = new Set();
    for (const patch of rows) {
      const id = patch.id ?? `legacy-${legacyId++}`;
      const location = byId.get(id);
      if (location) {
        const {group, index} = location;
        const old = group.rows[index];
        const text = patch.text === undefined ? old.text : settled || patch.replace ? patch.text : old.text + patch.text;
        if (text === old.text && (!patch.work || Object.keys(patch.work).every((key) => patch.work[key] === old.work?.[key]))) continue;
        if (!touched.has(group)) group.rows = [...group.rows];
        group.rows[index] = {...old, text, work: patch.work ?? old.work};
        touched.add(group);
      } else {
        const kind = patch.work?.kind;
        const work = !!patch.work;
        const special = kind === 'reasoning' || kind === 'plan';
        const previous = groups.at(-1);
        let group;
        if (work && !special && previous?.work && !previous.special) {
          group = previous;
          if (!touched.has(group)) group.rows = [...group.rows];
        } else {
          group = {id, work, special, rows: []};
          groups.push(group);
        }
        const index = group.rows.length;
        group.rows.push({...patch, id, text: patch.text ?? ''});
        byId.set(id, {group, index});
        touched.add(group);
      }
    }
    if (touched.size) flushSync(() => root.render(groups.map((group) => group.work
      ? <WorkGroup key={group.id} rows={group.rows} markdown={markdown}/>
      : <Message key={group.id} row={group.rows[0]} markdown={markdown}/>)));
  }
  return {apply};
}

window.embeddedChat = {
  mountTimeline, senderRuntimeModes, icon: (id) => <Icon id={id}/>,
  mountPermissions(element) {
    const root = createRoot(element);
    return (props) => flushSync(() => root.render(<PermissionModes {...props}/>));
  },
  mountSpeed(element) {
    const root = createRoot(element);
    return (props) => flushSync(() => root.render(<SpeedSegments {...props}/>));
  },
};
